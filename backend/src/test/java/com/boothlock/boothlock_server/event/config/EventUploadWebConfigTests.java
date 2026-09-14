package com.boothlock.boothlock_server.event.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 약도 폴더 설정 가드. 이 폴더는 인증 없이 열리므로 설정 한 줄 실수가 파일 시스템 노출로 이어진다 —
 * event-dir을 "."으로 두면 계좌번호·토큰이 담긴 H2 DB 파일이, "/"로 두면 /etc/passwd가 내려받아지는 것을 실측했다.
 */
class EventUploadWebConfigTests {

    private static final Path WORKDIR = Path.of("/srv/boothlock/backend");
    private static final String FILE_DB = "jdbc:h2:file:./data/boothlock;MODE=MySQL";

    @Test
    @DisplayName("기본값 data/uploads/event는 통과한다")
    void acceptsDefault() {
        assertThat(EventUploadWebConfig.validateUploadRoot("data/uploads/event", FILE_DB, WORKDIR))
                .isEqualTo(Path.of("/srv/boothlock/backend/data/uploads/event"));
    }

    @Test
    @DisplayName("작업 디렉터리 밖 절대 경로도 이름이 event면 통과한다 — 배포 서버 볼륨을 쓸 수 있어야 한다")
    void acceptsAbsoluteEventDirectory() {
        assertThat(EventUploadWebConfig.validateUploadRoot("/var/boothlock/uploads/event", FILE_DB, WORKDIR))
                .isEqualTo(Path.of("/var/boothlock/uploads/event"));
    }

    @Test
    @DisplayName("작업 디렉터리 자체(.)는 거부한다")
    void rejectsWorkingDirectory() {
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot(".", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boothlock.upload.event-dir");
    }

    @Test
    @DisplayName("루트(/)는 거부한다")
    void rejectsFilesystemRoot() {
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("/", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름이 event가 아닌 넓은 폴더(/etc, data)는 거부한다")
    void rejectsNonEventDirectories() {
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("/etc", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("data", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("data/uploads/..", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름이 event여도 작업 디렉터리의 상위면 거부한다")
    void rejectsAncestorNamedEvent() {
        Path workdir = Path.of("/srv/event/backend");
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("..", FILE_DB, workdir))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("파일 DB가 폴더 아래에 있으면 이름이 event여도 거부한다")
    void rejectsDirectoryContainingFileDatabase() {
        String dbInsideEvent = "jdbc:h2:file:./data/uploads/event/db/boothlock;MODE=MySQL";
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot("data/uploads/event", dbInsideEvent, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("데이터베이스");
    }

    @Test
    @DisplayName("빈 값은 거부한다")
    void rejectsBlank() {
        assertThatThrownBy(() -> EventUploadWebConfig.validateUploadRoot(" ", FILE_DB, WORKDIR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("파일 DB 경로 해석 — 옵션을 떼고, 메모리 DB·MySQL은 대상이 아니다")
    void parsesH2FileDatabasePath() {
        assertThat(EventUploadWebConfig.h2FileDatabasePath(FILE_DB, WORKDIR))
                .isEqualTo(Path.of("/srv/boothlock/backend/data/boothlock"));
        assertThat(EventUploadWebConfig.h2FileDatabasePath("jdbc:h2:mem:test;MODE=MySQL", WORKDIR)).isNull();
        assertThat(EventUploadWebConfig.h2FileDatabasePath("jdbc:mysql://db:3306/boothlock", WORKDIR)).isNull();
        assertThat(EventUploadWebConfig.h2FileDatabasePath("", WORKDIR)).isNull();
    }

    @Test
    @DisplayName("서빙 허용 경로 — 이미지 확장자만, 숨김 파일·확장자 없음 거부")
    void allowsOnlyImageFiles() {
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("map.png")).isTrue();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("2026/map.JPG")).isTrue();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("map.webp")).isTrue();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("evil.html")).isFalse();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("evil.svg")).isFalse();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath(".hidden.png")).isFalse();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath(".git/config.png")).isFalse();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("README")).isFalse();
        assertThat(EventUploadWebConfig.ImageOnlyResolver.isAllowedPath("dir.png/")).isFalse();
    }
}
