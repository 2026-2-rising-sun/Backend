package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 공개 개발 입력을 전달한다. 시나리오 상태와 선택은 Payment가 소유한다. */
@Component
@Profile("(local | test) & !dev & !prod")
@ConditionalOnProperty(
        prefix = "commercecore.dev.payment-scenario",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class DevPaymentScenarioRegistry {

    private final com.shoppinglive.commercecore.payment.service.PaymentClient client;
    private final com.shoppinglive.commercecore.order.repository.OrderJpaRepository orders;

    public DevPaymentScenarioRegistry(
            com.shoppinglive.commercecore.payment.service.PaymentClient client,
            com.shoppinglive.commercecore.order.repository.OrderJpaRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    private String reference(String number) {
        return orders.findByOrderNumber(number)
                .map(
                        o ->
                                o.getPaymentGroup() == null
                                        ? number
                                        : o.getPaymentGroup().getGroupNumber())
                .orElse(number);
    }

    public void set(String number, PaymentScenario scenario) {
        client.setScenario(reference(number), scenario);
    }

    public void clear(String number) {
        client.clearScenario(reference(number));
    }
}
