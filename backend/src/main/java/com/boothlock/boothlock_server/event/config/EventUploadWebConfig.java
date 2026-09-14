package com.boothlock.boothlock_server.event.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 행사 약도 이미지 서빙 (명세서 E2).
 * 메뉴 사진과 규칙은 같지만 설정 클래스를 따로 둔다 — MenuUploadWebConfig는 메뉴 파트 소유 파일이라
 * 거기에 경로를 추가하면 남의 파트를 고치게 된다. Spring은 WebMvcConfigurer 빈을 여러 개 합쳐서 적용한다.
 * 업로드 디렉터리도 menu-dir과 분리해 약도와 메뉴 사진이 섞이지 않게 한다.
 *
 * <p>이 경로는 인증 없이 열려 있으므로 세 겹으로 막는다.
 * <ol>
 *   <li>기동 시 폴더 설정 검증 — {@code event-dir}을 {@code .}이나 {@code /}로 잘못 두면
 *       DB 파일(계좌번호·토큰 포함)과 시스템 파일이 그대로 내려받아지는 것을 실측했다.</li>
 *   <li>서빙할 파일 제한 — 이미지 확장자만, 숨김 파일 제외, 폴더 밖을 가리키는 심볼릭 링크 제외.
 *       HTML·SVG가 폴더에 들어가면 같은 오리진에서 스크립트가 실행될 수 있다.</li>
 *   <li>응답 헤더 — nosniff와 스크립트 실행을 막는 CSP.</li>
 * </ol>
 */
@Configuration
public class EventUploadWebConfig implements WebMvcConfigurer {

    /** 약도는 총관리자가 넣는 사진 한 장이다. 벡터(SVG)는 스크립트를 품을 수 있어 받지 않는다 */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    private final Path uploadRoot;

    public EventUploadWebConfig(@Value("${boothlock.upload.event-dir:data/uploads/event}") String uploadDir,
                                @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.uploadRoot = validateUploadRoot(uploadDir, datasourceUrl, Path.of("").toAbsolutePath().normalize());
    }

    /**
     * 공개 폴더로 쓰기에 안전한 경로인지 확인한다. 하나라도 어긋나면 서버를 띄우지 않는다 —
     * 조용히 뜬 채로 파일 시스템을 열어 두는 것보다 기동 실패가 훨씬 낫다.
     */
    static Path validateUploadRoot(String uploadDir, String datasourceUrl, Path workingDir) {
        if (uploadDir == null || uploadDir.isBlank()) {
            throw invalid(uploadDir, "값이 비어 있습니다");
        }
        Path root = workingDir.resolve(uploadDir).toAbsolutePath().normalize();

        // 마지막 폴더 이름을 event로 고정한다. ".", "/", "data", "/etc" 같은 넓은 경로를 이름 하나로 걸러낸다
        Path lastName = root.getFileName();
        if (lastName == null || !lastName.toString().equals("event")) {
            throw invalid(uploadDir, "마지막 폴더 이름이 event여야 합니다 (예: data/uploads/event)");
        }
        // 작업 디렉터리 자체이거나 그 상위면 프로젝트 전체가 열린다
        if (workingDir.startsWith(root)) {
            throw invalid(uploadDir, "서버 작업 디렉터리이거나 그 상위 폴더입니다");
        }
        // 파일 DB가 이 폴더 아래에 있으면 계좌번호·토큰이 담긴 DB 파일이 내려받아진다
        Path databaseFile = h2FileDatabasePath(datasourceUrl, workingDir);
        if (databaseFile != null && databaseFile.startsWith(root)) {
            throw invalid(uploadDir, "데이터베이스 파일이 이 폴더 아래에 있습니다");
        }
        return root;
    }

    /** jdbc:h2:file:./data/boothlock;MODE=MySQL → 작업 디렉터리 기준 절대 경로. 파일 DB가 아니면 null */
    static Path h2FileDatabasePath(String datasourceUrl, Path workingDir) {
        String prefix = "jdbc:h2:file:";
        if (datasourceUrl == null || !datasourceUrl.startsWith(prefix)) {
            return null;
        }
        String location = datasourceUrl.substring(prefix.length());
        int options = location.indexOf(';');
        if (options >= 0) {
            location = location.substring(0, options);
        }
        if (location.startsWith("~")) {
            location = System.getProperty("user.home") + location.substring(1);
        }
        return workingDir.resolve(location).toAbsolutePath().normalize();
    }

    private static IllegalArgumentException invalid(String uploadDir, String reason) {
        return new IllegalArgumentException(
                "boothlock.upload.event-dir 설정이 공개 폴더로 안전하지 않습니다 — " + reason + ". 현재 값: " + uploadDir);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/event/**")
                .addResourceLocations(uploadRoot.toUri().toString())
                .setCachePeriod(3600)
                // 결과 캐시를 끈다 — 약도 교체 직후 이전 판정(없음/있음)이 남지 않게
                .resourceChain(false)
                .addResolver(new ImageOnlyResolver(uploadRoot));
    }

    /** 브라우저가 MIME을 추측하는 시점은 파일을 내려받을 때다 — 서빙 응답에 헤더가 있어야 의미가 있다 (명세서 O9 ④와 동일 규칙) */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Content-Type-Options", "nosniff");
                // <img>로 표시하는 데는 영향이 없고, 문서로 열렸을 때 스크립트 실행만 막는다
                response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                return true;
            }
        }).addPathPatterns("/uploads/event/**");
    }

    /**
     * 경로 탈출은 Spring 기본 리졸버가 이미 막는다. 여기서는 그 뒤에 남는 구멍 셋을 닫는다 —
     * 이미지가 아닌 파일, 숨김 파일, 폴더 밖을 가리키는 심볼릭 링크. 조건에 안 맞으면 없는 파일(404)로 응답한다.
     */
    static final class ImageOnlyResolver extends PathResourceResolver {

        private final Path uploadRoot;

        ImageOnlyResolver(Path uploadRoot) {
            this.uploadRoot = uploadRoot;
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (!isAllowedPath(resourcePath)) {
                return null;
            }
            Resource resource = super.getResource(resourcePath, location);
            if (resource == null || !resource.isFile()) {
                return null;
            }
            Path real = resource.getFile().toPath().toRealPath();
            if (!Files.isRegularFile(real) || !Files.exists(uploadRoot) || !real.startsWith(uploadRoot.toRealPath())) {
                return null;
            }
            return resource;
        }

        static boolean isAllowedPath(String resourcePath) {
            for (String segment : resourcePath.split("/")) {
                if (segment.startsWith(".")) {
                    return false;
                }
            }
            int dot = resourcePath.lastIndexOf('.');
            int slash = resourcePath.lastIndexOf('/');
            if (dot <= slash + 1) {
                return false;
            }
            return ALLOWED_EXTENSIONS.contains(resourcePath.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
    }
}
