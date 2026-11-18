package com.shoppinglive.payment.payment.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 승인 API 설정. 응답 대기는 Commerce 호출 타임아웃(10초)보다 짧아야 한다(ADR 0337-5). */
@ConfigurationProperties("payment.approval")
public record ApprovalProperties(@DefaultValue("8s") Duration responseWait) {
    public ApprovalProperties {
        if (responseWait.isNegative() || responseWait.compareTo(Duration.ofSeconds(10)) >= 0) {
            throw new IllegalArgumentException(
                    "payment.approval.response-wait는 0 이상 10초 미만이어야 합니다: " + responseWait);
        }
    }
}
