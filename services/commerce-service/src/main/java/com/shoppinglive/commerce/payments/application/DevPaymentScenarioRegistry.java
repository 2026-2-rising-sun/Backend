package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 명시적으로 켠 local/test 환경에서만 제공하는 Mock 결제 결과 제어. */
@Component
@Profile("(local | test) & !dev & !prod")
@ConditionalOnProperty(
    prefix = "commerce.dev.payment-scenario",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class DevPaymentScenarioRegistry {

    private final ConcurrentMap<String, PaymentScenario> map = new ConcurrentHashMap<>();

    public void set(String orderNumber, PaymentScenario scenario) {
        map.put(orderNumber, scenario);
    }

    public Optional<PaymentScenario> get(String orderNumber) {
        return Optional.ofNullable(map.get(orderNumber));
    }

    public void clear(String orderNumber) {
        map.remove(orderNumber);
    }

    public int size() {
        return map.size();
    }
}
