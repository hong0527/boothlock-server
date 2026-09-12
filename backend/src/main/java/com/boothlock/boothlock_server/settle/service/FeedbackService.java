package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.settle.domain.FeedbackEntity;
import com.boothlock.boothlock_server.settle.dto.FeedbackRequest;
import com.boothlock.boothlock_server.settle.repository.FeedbackRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;

    public FeedbackService(FeedbackRepository feedbackRepository, BoothJwtProvider jwtProvider,
            BoothInfoService boothInfoService) {
        this.feedbackRepository = feedbackRepository;
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
    }

    public Long saveFeedback(String authorization, FeedbackRequest request) {
        Jwt jwt = jwtProvider.verify(authorization);
        StaffAccountEntity staff = boothInfoService.authenticate(jwt);
        BoothEntity staffBooth = staff.getBooth();
        if (staffBooth == null) {
            throw new UnauthorizedException();
        }
        Long boothId = staffBooth.getId();
        Long staffId = staff.getId();

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