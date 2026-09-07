package com.boothlock.boothlock_server.menu.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MenuUploadApiTests {

    private static final Path UPLOAD_DIR = Path.of("build", "uploads", "menu");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MenuRepository menuRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        cleanUploadDir();
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("업로드 부스", "은행 1234", "10:00~20:00"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("correct-password");
        staffAccountRepository.save(new StaffAccountEntity(
                booth, "upload-admin", hash, LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.STAFF));
        token = login("upload-admin", "correct-password");
    }

    @AfterEach
    void tearDown() throws Exception {
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
        cleanUploadDir();
    }

    @Test
    void uploadsImageWithRandomUrlAndNosniff() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "original.png", MediaType.IMAGE_PNG_VALUE, imageBytes("png", 1200, 600));

        String response = mockMvc.perform(multipart("/api/v1/admin/uploads")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/uploads/menu/")))
                .andReturn().getResponse().getContentAsString();

        String url = objectMapper.readTree(response).get("url").asText();
        assertThat(url).endsWith(".jpg");
        assertThat(url).doesNotContain("original");

        Path stored = UPLOAD_DIR.resolve(url.substring("/uploads/menu/".length()));
        assertThat(stored).exists();
        byte[] storedBytes = Files.readAllBytes(stored);
        assertThat(storedBytes[0] & 0xFF).isEqualTo(0xFF);
        assertThat(storedBytes[1] & 0xFF).isEqualTo(0xD8);

        BufferedImage storedImage = ImageIO.read(stored.toFile());
        assertThat(Math.max(storedImage.getWidth(), storedImage.getHeight())).isLessThanOrEqualTo(1080);

        mockMvc.perform(get(url))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/uploads")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("파일이 첨부되지 않았습니다."));
    }

    @Test
    void rejectsSvgEvenWhenMultipartFileIsProvided() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.svg", "image/svg+xml", "<svg><script>alert(1)</script></svg>".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/uploads")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("지원하지 않는 파일 형식입니다."));
    }

    @Test
    void rejectsMissingAuthorizationBeforeSavingFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "menu.png", MediaType.IMAGE_PNG_VALUE, imageBytes("png", 10, 10));

        mockMvc.perform(multipart("/api/v1/admin/uploads").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertThat(Files.exists(UPLOAD_DIR)).isFalse();
    }

    private String login(String loginId, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private void cleanUploadDir() throws Exception {
        if (!Files.exists(UPLOAD_DIR)) {
            return;
        }
        try (var paths = Files.walk(UPLOAD_DIR)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }

    private record Credentials(String loginId, String password) {
    }
}
