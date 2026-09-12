package com.boothlock.boothlock_server.menu.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class MenuUploadWebConfig implements WebMvcConfigurer {

    private final Path uploadRoot;

    public MenuUploadWebConfig(@Value("${boothlock.upload.menu-dir:data/uploads/menu}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/menu/**")
                .addResourceLocations(uploadRoot.toUri().toString())
                .setCachePeriod(3600);
    }

    /**
     * 명세 O9의 nosniff는 파일을 내려주는 시점에 필요하다.
     * 업로드 201 응답에만 붙이면 브라우저가 실제로 스니핑하는 GET /uploads/menu/{파일} 시점에는 헤더가 없다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Content-Type-Options", "nosniff");
                return true;
            }
        }).addPathPatterns("/uploads/menu/**");
    }
}
