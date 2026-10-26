package com.shoppinglive.commerce.order.service;

import com.shoppinglive.commerce.order.repository.PaymentGroupRepository;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentGroupExpirationScheduler {
    private final PaymentGroupRepository groups;
    private final PaymentGroupService service;

    public PaymentGroupExpirationScheduler(
            PaymentGroupRepository groups, PaymentGroupService service) {
        this.groups = groups;
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void run() {
        for (Long id : groups.expired(Instant.now(), PageRequest.of(0, 100))) {
            try {
                service.expire(id);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("결제 그룹 만료 처리 실패: 결제 그룹 ID={}", id, e);
            }
        }
    }
}
