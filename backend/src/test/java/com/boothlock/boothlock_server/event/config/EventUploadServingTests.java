package com.boothlock.boothlock_server.event.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약도 정적 서빙을 실제 파일로 확인한다 (명세 E2). 인증 없이 열린 경로라 이미지 말고는 아무것도 나가면 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventUploadServingTests {

    private static final Path BASE;
    private static final Path EVENT_DIR;
    private static final Path OUTSIDE_SECRET;
    /** Windows는 관리자 권한이나 개발자 모드가 없으면 심볼릭 링크를 만들 수 없다 */
    private static final boolean SYMLINK_CREATED;

    static {
        try {
            BASE = Files.createTempDirectory("boothlock-serving-test").toRealPath();
            EVENT_DIR = Files.createDirectories(BASE.resolve("uploads/event"));
            Files.write(EVENT_DIR.resolve("map.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
            Files.writeString(EVENT_DIR.resolve("evil.html"), "<script>alert(1)</script>");
            Files.writeString(EVENT_DIR.resolve("evil.svg"), "<svg onload=alert(1)></svg>");
            Files.write(EVENT_DIR.resolve(".hidden.png"), new byte[]{1});
            OUTSIDE_SECRET = Files.writeString(BASE.resolve("secret.png"), "SECRET-OUTSIDE");
            SYMLINK_CREATED = tryCreateSymbolicLink(EVENT_DIR.resolve("link.png"), OUTSIDE_SECRET);
            Files.createDirectories(BASE.resolve("uploads/menu"));
            Files.write(BASE.resolve("uploads/menu/dish.png"), new byte[]{9});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean tryCreateSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (FileSystemException | UnsupportedOperationException e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void uploadDirs(DynamicPropertyRegistry registry) {
        registry.add("boothlock.upload.event-dir", EVENT_DIR::toString);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (Stream<Path> paths = Files.walk(BASE)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("이미지는 200과 nosniff·CSP 헤더로 서빙한다")
    void servesImageWithSecurityHeaders() throws Exception {
        mockMvc.perform(get("/uploads/event/map.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", EventUploadWebConfig.CONTENT_SECURITY_POLICY));
    }

    @Test
    @DisplayName("HTML·SVG는 폴더에 있어도 404 — 같은 오리진에서 스크립트가 실행되면 안 된다")
    void refusesScriptCapableFiles() throws Exception {
        mockMvc.perform(get("/uploads/event/evil.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/event/evil.svg")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("숨김 파일은 404")
    void refusesHiddenFiles() throws Exception {
        mockMvc.perform(get("/uploads/event/.hidden.png")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("폴더 밖을 가리키는 심볼릭 링크는 확장자가 png여도 404")
    void refusesSymlinkEscapingFolder() throws Exception {
        assumeTrue(SYMLINK_CREATED, "이 환경에서는 심볼릭 링크를 만들 수 없다 (Windows: 관리자 권한 또는 개발자 모드 필요)");
        mockMvc.perform(get("/uploads/event/link.png"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET-OUTSIDE"))));
    }

    @Test
    @DisplayName("상위 폴더로 빠져나가는 요청은 404 — 옆 폴더 파일도 안 나간다")
    void refusesTraversal() throws Exception {
        mockMvc.perform(get("/uploads/event/../secret.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/event/../menu/dish.png")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 파일은 404")
    void missingFileIsNotFound() throws Exception {
        mockMvc.perform(get("/uploads/event/nope.png")).andExpect(status().isNotFound());
    }
}
