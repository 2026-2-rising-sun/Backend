package com.shoppinglive.payment.payment.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 결과 불명 복구 설정(#349). 주기는 {@link RecoveryScheduler}가 같은 키로 읽는다. */
@ConfigurationProperties("payment.recovery")
public record RecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration sweepInterval,
        @DefaultValue("100") int sweepBatchSize,
        // 토스 호출 최대 60초 × 2보다 길게
        @DefaultValue("3m") Duration approvingStuckAfter,
        // 첫 조회 10초를 지키려면 더 짧아야 한다
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("50") int pollBatchSize,
        @DefaultValue("30m") Duration checkDeadline,
        // 고객 환불 자동 재시도 상한. 토스 멱등 키 유효기간(15일)보다 짧아야 한다
        @DefaultValue("24h") Duration refundGiveUpAfter,
        // 첫 시도부터 checkDeadline이 지난 고객 환불의 재시도 간격
        @DefaultValue("10m") Duration refundSlowRetryInterval) {}
