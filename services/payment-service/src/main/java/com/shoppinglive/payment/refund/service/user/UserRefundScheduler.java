package com.shoppinglive.payment.refund.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 끝나지 못한 고객 환불의 주기 실행. 분산 락 없이 {@code claim} 조건부 UPDATE로 한 인스턴스만 집는다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "payment.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
class UserRefundScheduler {
    private final UserRefundExecutor executor;

    @Scheduled(fixedDelayString = "${payment.recovery.poll-interval:5s}")
    void retry() {
        executor.retryDue();
    }
}
