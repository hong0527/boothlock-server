package com.boothlock.boothlock_server.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 행사 약도 (DB스키마 v1.3 event_map) — 손님 홈 화면의 배경 그림.
 * 부스와 FK가 없다: 핀 위치는 booth.mapX·mapY가 갖고 이 엔티티는 그림만 갖는다.
 * 약도를 교체해도 부스 좌표가 상대값이라 그대로 유효하다.
 * 파일럿은 행이 1건이며 등록은 시딩으로 한다 (행사당 한 장이라 업로드 API를 두지 않는다).
 */
@Entity
@Table(name = "event_map")
public class EventMapEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_url", nullable = false, length = 300)
    private String imageUrl;

    /** 원본 px — 클라이언트가 0~10000 상대 좌표를 화면 픽셀로 환산할 때 쓴다 */
    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected EventMapEntity() {
    }

    public EventMapEntity(String imageUrl, int width, int height, LocalDateTime updatedAt) {
        this.imageUrl = imageUrl;
        this.width = width;
        this.height = height;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
