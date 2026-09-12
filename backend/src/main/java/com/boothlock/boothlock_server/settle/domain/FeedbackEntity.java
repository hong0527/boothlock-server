package com.boothlock.boothlock_server.settle.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "feedback")
public class FeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false)
    private Long boothId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(nullable = false)
    private Integer rating;

    @Column(name = "easy_setup", nullable = false)
    private Boolean easySetup;

    @Column(name = "easy_orders", nullable = false)
    private Boolean easyOrders;

    @Column(name = "would_reuse", nullable = false)
    private Boolean wouldReuse;

    @Column(length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected FeedbackEntity() {
    }

    public FeedbackEntity(
            Long boothId,
            Long staffId,
            Integer rating,
            Boolean easySetup,
            Boolean easyOrders,
            Boolean wouldReuse,
            String comment,
            LocalDateTime createdAt
    ) {
        this.boothId = boothId;
        this.staffId = staffId;
        this.rating = rating;
        this.easySetup = easySetup;
        this.easyOrders = easyOrders;
        this.wouldReuse = wouldReuse;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBoothId() {
        return boothId;
    }

    public Long getStaffId() {
        return staffId;
    }

    public Integer getRating() {
        return rating;
    }

    public Boolean getEasySetup() {
        return easySetup;
    }

    public Boolean getEasyOrders() {
        return easyOrders;
    }

    public Boolean getWouldReuse() {
        return wouldReuse;
    }

    public String getComment() {
        return comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
