package com.boothlock.boothlock_server.menu.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.UploadBusyException;
import com.boothlock.boothlock_server.menu.dto.MenuUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class MenuImageUploadService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_SIDE = 1080;
    // 압축 후 크기(5MB)로는 못 막는 이미지 폭탄 방어선 — 5000만 화소면 트루컬러 래스터가 약 200MB다
    private static final long MAX_PIXELS = 50_000_000L;
    // 디코딩 해상도 상한(긴 변) — 출력(MAX_SIDE)의 2배까지만 풀어 놓는다. 5000만 화소 상한만으로는 한 장에 약 200MB 래스터가
    // 잡혀, 서버 힙이 작으면 업로드 한두 건이 주문 결제 요청까지 OOM으로 함께 죽인다. 2배면 1080px로 줄일 때 재료가 충분하다
    private static final int DECODE_MAX_SIDE = MAX_SIDE * 2;
    private static final String PUBLIC_PATH = "/uploads/menu/";

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final Path uploadRoot;
    private final long uploadWaitMillis;
    /**
     * 디코딩·축소·저장을 서버 전체에서 한 번에 하나로 줄 세운다. 서브샘플링으로 한 장의 래스터는 작아졌지만
     * 운영자 여러 명이 큰 사진을 동시에 올리면 그만큼 곱해진다 — 메뉴 사진 업로드는 드문 작업이라 직렬화해도 체감이 없다.
     * 공정(fair) 모드라 먼저 온 업로드가 먼저 들어간다. 테스트가 퍼밋을 직접 쥐어 "바쁨" 경로를 재현하도록 패키지 범위로 둔다
     */
    final Semaphore uploadPermits = new Semaphore(1, true);

    public MenuImageUploadService(
            BoothJwtProvider jwtProvider,
            BoothInfoService boothInfoService,
            @Value("${boothlock.upload.menu-dir:data/uploads/menu}") String uploadDir,
            // 앞 업로드를 기다리는 최대 시간 — 정상 사진 한 장 처리보다 넉넉하고, 요청 스레드를 오래 붙잡지 않을 만큼 짧게
            @Value("${boothlock.upload.menu-wait-millis:10000}") long uploadWaitMillis) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
        this.uploadWaitMillis = uploadWaitMillis;
    }

    public MenuUploadResponse upload(String authorization, MultipartFile file) {
        authenticateStaffWithBooth(authorization);
        byte[] bytes = readAndValidate(file);

        // 인증·형식 검사는 줄 밖에서 한다 — 거절될 요청이 줄을 차지하지 않게. 메모리를 크게 쓰는 디코딩부터 저장까지만 줄을 선다
        try {
            if (!uploadPermits.tryAcquire(uploadWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new UploadBusyException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UploadBusyException();
        }
        try {
            return new MenuUploadResponse(PUBLIC_PATH + store(resize(decode(bytes))));
        } finally {
            uploadPermits.release();
        }
    }

    private String store(BufferedImage resized) {
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
        return fileName;
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
     *
     * <p>상한 안쪽이어도 원본 해상도 그대로 풀지 않는다 — 긴 변이 {@link #DECODE_MAX_SIDE}를 넘으면 소스 서브샘플링
     * (N픽셀마다 1픽셀만 읽기)으로 디코딩 단계에서 줄인다. 간격은 올림으로 잡아 디코딩 결과의 긴 변이 DECODE_MAX_SIDE 이하가 되고,
     * 긴 변이 DECODE_MAX_SIDE를 넘는 원본에서는 결과가 항상 MAX_SIDE 이상이라 축소(resize) 재료가 모자라지 않는다.
     * 휴대폰 사진(4032x3024)은 2픽셀 간격으로 2016x1512만 풀린다(래스터 메모리 약 1/4).
     * 반환 크기는 서브샘플링 전 원본 크기다 — 올림 때문에 가로·세로가 1픽셀씩 어긋날 수 있어 출력 비율은 원본으로 계산한다
     */
    Decoded decode(byte[] bytes) {
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
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > MAX_PIXELS) {
                    throw new InvalidRequestException("이미지 해상도가 너무 큽니다. 5000만 화소 이하로 올려주세요.");
                }
                int step = (Math.max(width, height) + DECODE_MAX_SIDE - 1) / DECODE_MAX_SIDE;
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
                }
                return new Decoded(image, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
        }
    }

    /** 디코딩된 래스터(서브샘플링됐을 수 있음)와 서브샘플링 전 원본 크기 */
    record Decoded(BufferedImage image, int originalWidth, int originalHeight) {
    }

    /** 출력 크기는 원본 크기로 계산한다 — 서브샘플링 전과 똑같은 크기가 나온다 */
    private BufferedImage resize(Decoded decoded) {
        BufferedImage source = decoded.image();
        int max = Math.max(decoded.originalWidth(), decoded.originalHeight());
        double scale = max > MAX_SIDE ? (double) MAX_SIDE / max : 1.0;
        int width = Math.max(1, (int) Math.round(decoded.originalWidth() * scale));
        int height = Math.max(1, (int) Math.round(decoded.originalHeight() * scale));

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
