package com.shoppinglive.payment.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 복구·만료 작업 3개의 주기 실행. {@code payment.recovery.enabled=false}면 등록하지 않는다(통합 테스트는 작업 메서드를 직접 부른다). 분산
 * 락은 없다. 작업마다 조건부 UPDATE라 여러 인스턴스가 같이 돌아도 결과는 1번 반영된다(PAY-RECOVER-05).
 */
@Component
@ConditionalOnProperty(
        name = "payment.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RecoveryScheduler {
    private final ApprovingSweeper sweeper;
    private final UnknownPaymentPoller poller;
    private final PaymentExpiryJob expiry;

    public RecoveryScheduler(
            ApprovingSweeper sweeper, UnknownPaymentPoller poller, PaymentExpiryJob expiry) {
        this.sweeper = sweeper;
        this.poller = poller;
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${payment.recovery.sweep-interval:30s}")
    void sweep() {
        sweeper.sweep();
    }

    @Scheduled(fixedDelayString = "${payment.recovery.poll-interval:5s}")
    void poll() {
        poller.poll();
    }

    @Scheduled(fixedDelayString = "${payment.expiry.interval:1m}")
    void expire() {
        expiry.expire();
    }
}
