package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentGroupExpirationScheduler {
    private final PaymentGroupRepository groups;
    private final PaymentGroupService service;
    private final OrderTimeSource time;

    public PaymentGroupExpirationScheduler(
            PaymentGroupRepository groups, PaymentGroupService service, OrderTimeSource time) {
        this.groups = groups;
        this.service = service;
        this.time = time;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void run() {
        for (Long id : groups.expired(time.now(), PageRequest.of(0, 100))) {
            try {
                service.expire(id);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("결제 그룹 만료 처리 실패: 결제 그룹 ID={}", id, e);
            }
        }
    }
}
