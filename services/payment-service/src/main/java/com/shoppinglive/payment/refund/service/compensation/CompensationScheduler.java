package com.shoppinglive.payment.refund.service.compensation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 보상 재처리 주기 실행. 복구 작업과 같은 스위치를 쓴다. 분산 락 대신 {@code claim}이 한 인스턴스만 통과시킨다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "payment.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
class CompensationScheduler {
    private final CompensationRetryJob job;

    @Scheduled(fixedDelayString = "${payment.refunds.compensation.poll-interval:5s}")
    void run() {
        job.run();
    }
}
