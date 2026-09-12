package com.boothlock.boothlock_server.menu.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.menu.dto.MenuUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Service
public class MenuImageUploadService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_SIDE = 1080;
    // 압축 후 크기(5MB)로는 못 막는 이미지 폭탄 방어선 — 5000만 화소면 트루컬러 래스터가 약 200MB다
    private static final long MAX_PIXELS = 50_000_000L;
    private static final String PUBLIC_PATH = "/uploads/menu/";

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final Path uploadRoot;

    public MenuImageUploadService(
            BoothJwtProvider jwtProvider,
            BoothInfoService boothInfoService,
            @Value("${boothlock.upload.menu-dir:data/uploads/menu}") String uploadDir) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public MenuUploadResponse upload(String authorization, MultipartFile file) {
        authenticateStaffWithBooth(authorization);
        byte[] bytes = readAndValidate(file);
        BufferedImage image = decode(bytes);
        BufferedImage resized = resize(image);

        String fileName = UUID.randomUUID() + ".jpg";
        Path target = uploadRoot.resolve(fileName).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new InvalidRequestException("파일명이 올바르지 않습니다.");
        }

        try {
            Files.createDirectories(uploadRoot);
            if (!ImageIO.write(resized, "jpg", target.toFile())) {
                throw new IOException("JPEG writer not found");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("이미지 저장에 실패했습니다.", exception);
        }

        return new MenuUploadResponse(PUBLIC_PATH + fileName);
    }

    private void authenticateStaffWithBooth(String authorization) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        if (staff.getBooth() == null) {
            throw new ForbiddenException();
        }
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("파일이 첨부되지 않았습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidRequestException("파일 크기는 5MB 이하여야 합니다.");
        }

        try {
            byte[] bytes = file.getBytes();
            if (isSvg(bytes) || !isAllowedImage(bytes)) {
                throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
            }
            return bytes;
        } catch (IOException exception) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
    }

    /**
     * 디코딩 전에 헤더만 읽어 픽셀 수를 먼저 본다.
     * 파일 크기 상한(5MB)은 압축 후 크기라 방어가 되지 않는다 — 1MB짜리 20000x20000 PNG의 래스터는 1GB가 넘어
     * ImageIO.read를 그대로 부르면 힙이 고갈되고 같은 서버의 주문 결제 요청까지 함께 죽는다.
     */
    private BufferedImage decode(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new InvalidRequestException("이미지 해상도가 너무 큽니다. 5000만 화소 이하로 올려주세요.");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int max = Math.max(source.getWidth(), source.getHeight());
        double scale = max > MAX_SIDE ? (double) MAX_SIDE / max : 1.0;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private boolean isAllowedImage(byte[] bytes) {
        return isJpeg(bytes) || isPng(bytes) || isWebp(bytes);
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && unsigned(bytes[0]) == 0xFF
                && unsigned(bytes[1]) == 0xD8
                && unsigned(bytes[2]) == 0xFF;
    }

    private boolean isPng(byte[] bytes) {
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (unsigned(bytes[i]) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && ascii(bytes, 0, 4).equals("RIFF")
                && ascii(bytes, 8, 4).equals("WEBP");
    }

    private boolean isSvg(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8)
                .trim()
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<svg") || head.contains("<svg");
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
}
