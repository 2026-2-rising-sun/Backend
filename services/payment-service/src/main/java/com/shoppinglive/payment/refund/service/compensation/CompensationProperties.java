package com.shoppinglive.payment.refund.service.compensation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 시스템 보상 재처리 설정(#386). 주기는 {@link CompensationScheduler}가 같은 키로 읽는다. */
@ConfigurationProperties("payment.refunds.compensation")
public record CompensationProperties(
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        // 첫 CONFIRM + 2×응답 한도 뒤에 토스 반영이 늦는 경우를 덮는 여유
        @DefaultValue("30s") Duration confirmGrace,
        @DefaultValue("30s") Duration retryBase,
        @DefaultValue("10m") Duration retryMax,
        // 30초부터 두 배씩이면 약 1시간 뒤다. 알림만 남기고 재시도는 멈추지 않는다
        @DefaultValue("10") int humanCheckAfter) {}
