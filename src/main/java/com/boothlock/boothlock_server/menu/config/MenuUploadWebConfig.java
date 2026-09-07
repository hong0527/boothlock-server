package com.boothlock.boothlock_server.menu.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class MenuUploadWebConfig implements WebMvcConfigurer {

    private final Path uploadRoot;

    public MenuUploadWebConfig(@Value("${boothlock.upload.menu-dir:build/uploads/menu}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/menu/**")
                .addResourceLocations(uploadRoot.toUri().toString())
                .setCachePeriod(3600);
    }
}
