package com.boothlock.boothlock_server.menu.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.UploadBusyException;
import com.boothlock.boothlock_server.menu.dto.MenuUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 메뉴 이미지 업로드의 메모리 방어 — 디코딩 서브샘플링(긴 변 2160px 이하)과 서버 전체 직렬화(Semaphore).
 * 인증은 목으로 통과시키고 서비스만 직접 부른다(업로드 API 전체 흐름은 MenuUploadApiTests).
 */
class MenuImageUploadServiceTests {

    @TempDir Path uploadDir;

    private BoothJwtProvider jwtProvider;
    private BoothInfoService boothInfoService;

    @BeforeEach
    void setUp() {
        jwtProvider = mock(BoothJwtProvider.class);
        boothInfoService = mock(BoothInfoService.class);
        StaffAccountEntity staff = new StaffAccountEntity(new BoothEntity("업로드 부스", "은행 1234", null),
                "upload-staff", "hash", LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF);
        when(boothInfoService.authenticate(any())).thenReturn(staff);
    }

    private MenuImageUploadService service(long waitMillis) {
        return new MenuImageUploadService(jwtProvider, boothInfoService, uploadDir.toString(), waitMillis);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test
    void decodesLargeImageAtMostTwiceTheOutputSide() throws Exception {
        MenuImageUploadService.Decoded decoded = service(1000).decode(image("png", 7001, 3001));

        assertThat(Math.max(decoded.image().getWidth(), decoded.image().getHeight()))
                .isLessThanOrEqualTo(2160)
                .isGreaterThanOrEqualTo(1080);
        assertThat(decoded.originalWidth()).isEqualTo(7001);
        assertThat(decoded.originalHeight()).isEqualTo(3001);
    }

    @Test
    void doesNotSubsampleImagesThatAreAlreadySmall() throws Exception {
        MenuImageUploadService.Decoded decoded = service(1000).decode(image("jpg", 2160, 1000));

        assertThat(decoded.image().getWidth()).isEqualTo(2160);
        assertThat(decoded.image().getHeight()).isEqualTo(1000);
    }

    /** 큰 사진도 출력 크기는 서브샘플링 전과 같다 — 원본 비율로 1080px에 맞춘다 */
    @Test
    void largeImageStillProducesExactOutputDimensions() throws Exception {
        MenuImageUploadService service = service(1000);
        assertStoredSize(service, image("png", 7001, 3001), 1080, 463);    // 3001 * 1080 / 7001 = 462.9
        assertStoredSize(service, image("jpg", 4032, 3024), 1080, 810);    // 휴대폰 사진
        assertStoredSize(service, image("jpg", 3000, 5000), 648, 1080);    // 세로 사진
        assertStoredSize(service, image("png", 800, 600), 800, 600);       // 작은 사진은 그대로
    }

    private void assertStoredSize(MenuImageUploadService service, byte[] bytes, int width, int height) throws Exception {
        MenuUploadResponse response = service.upload("Bearer x",
                new MockMultipartFile("file", "menu", "application/octet-stream", bytes));
        Path stored = uploadDir.resolve(response.url().substring("/uploads/menu/".length()));
        BufferedImage image = ImageIO.read(stored.toFile());
        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
    }

    /** 앞 업로드가 퍼밋을 쥐고 있으면 대기 시간 뒤 503 UPLOAD_BUSY — 아무것도 저장하지 않고, 퍼밋이 풀리면 다시 된다 */
    @Test
    void rejectsUploadWhileAnotherIsInProgressAndRecoversAfterRelease() throws Exception {
        MenuImageUploadService service = service(50);
        MockMultipartFile file = new MockMultipartFile("file", "menu", "image/png", image("png", 40, 40));

        service.uploadPermits.acquire();
        try {
            assertThatThrownBy(() -> service.upload("Bearer x", file)).isInstanceOf(UploadBusyException.class);
            try (var files = Files.list(uploadDir)) {
                assertThat(files).isEmpty();
            }
        } finally {
            service.uploadPermits.release();
        }

        service.upload("Bearer x", file);
        assertThat(service.uploadPermits.availablePermits()).isEqualTo(1);   // 성공 뒤 퍼밋을 돌려놓는다
    }

    /** 디코딩이 실패해도(400) 퍼밋은 반드시 돌려놓는다 — 새면 그 뒤 업로드가 전부 503이 된다 */
    @Test
    void releasesPermitWhenDecodingFails() throws Exception {
        MenuImageUploadService service = service(50);
        byte[] broken = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        assertThatThrownBy(() -> service.upload("Bearer x",
                new MockMultipartFile("file", "menu", "image/png", broken))).isNotInstanceOf(UploadBusyException.class);
        assertThat(service.uploadPermits.availablePermits()).isEqualTo(1);
    }
}
