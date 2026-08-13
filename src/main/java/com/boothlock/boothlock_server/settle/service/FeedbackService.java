package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.settle.domain.FeedbackEntity;
import com.boothlock.boothlock_server.settle.dto.FeedbackRequest;
import com.boothlock.boothlock_server.settle.repository.FeedbackRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    public Long saveFeedback(Long boothId, Long staffId, FeedbackRequest request) {

        FeedbackEntity feedback = new FeedbackEntity(
                boothId,
                staffId,
                request.rating(),
                request.easySetup(),
                request.easyOrders(),
                request.wouldReuse(),
                request.comment(),
                LocalDateTime.now()
        );

        FeedbackEntity savedFeedback = feedbackRepository.save(feedback);

        return savedFeedback.getId();
    }
}