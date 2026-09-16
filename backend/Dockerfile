# syntax=docker/dockerfile:1
# 부스락 API 서버 이미지 — 멀티스테이지: Gradle 빌드(JDK 21) → 실행(JRE 21, 비루트)
#   빌드:  docker build -t boothlock-server:latest backend/
#   실행:  루트의 docker-compose.prod.yml 참조 (환경변수·업로드 볼륨을 거기서 넣는다)

# ---------- 1단계: 빌드 ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 해석은 소스와 분리해 캐시한다 — 소스만 바뀌면 이 레이어는 재사용된다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && (./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true)

COPY src ./src
# 테스트는 CI(.github/workflows/build.yml)에서 돈다. 이미지 빌드에서는 jar만 만든다
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:21-jre
# 고정 UID/GID — 호스트에 바인드 마운트한 업로드 폴더를 이 번호로 chown 한다 (배포 문서 참조)
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app/data/uploads/menu /app/data/uploads/event /app/seed \
 && chown -R app:app /app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

# 영업일(06:00 경계)·DATETIME 해석은 KST 전제 (DB스키마 원칙 8). build.gradle의 -Duser.timezone은 bootRun/test에만 걸리므로
# 컨테이너에서는 여기서 고정한다. MaxRAMPercentage: 컨테이너 메모리 제한 안에서 힙을 잡는다 (기본 25%는 너무 작다)
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod \
    BOOTLOCK_UPLOAD_MENU_DIR=/app/data/uploads/menu \
    BOOTLOCK_UPLOAD_EVENT_DIR=/app/data/uploads/event

# 메뉴 사진(O9)·행사 약도(E2) — 컨테이너를 갈아도 남아야 하므로 볼륨
VOLUME ["/app/data/uploads"]
EXPOSE 8080

# E1(부스 목록)은 인증 없이 200을 준다. /map은 약도 등록 전 404라 쓰지 않는다
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/v1/event/booths > /dev/null || exit 1

USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
