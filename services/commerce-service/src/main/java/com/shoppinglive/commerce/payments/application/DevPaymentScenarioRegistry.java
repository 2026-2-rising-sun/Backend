package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 결제 시나리오 in-memory 레지스트리 (결제 4).
 *
 * <p>주문번호별로 결제 시작 시 사용할 시나리오를 사전에 지정할 수 있게 한다. Production
 * 배포에는 활성화되지 않는다.
 *
 * <ul>
 *   <li>{@code @Profile("!prod")} — prod 프로파일에서는 빈 등록 안 됨</li>
 *   <li>{@code @ConditionalOnProperty} — 설정으로 명시적 비활성 가능
 *       ({@code commerce.dev.payment-scenario.enabled: false})</li>
 * </ul>
 *
 * <p>운영 환경에는 결제 시작 요청 body 의 scenario 필드도 무시되어야 하지만, 현재
 * PaymentService 는 body scenario 를 우선 채택하므로 향후 별도 격리 필요.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(
    prefix = "commerce.dev.payment-scenario",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
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
