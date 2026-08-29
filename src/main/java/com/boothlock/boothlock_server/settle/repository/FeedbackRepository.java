package com.boothlock.boothlock_server.settle.repository;

import com.boothlock.boothlock_server.settle.domain.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {
}