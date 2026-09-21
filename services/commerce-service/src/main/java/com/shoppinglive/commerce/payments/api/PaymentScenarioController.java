package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.application.DevPaymentScenarioRegistry;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 전용 결제 시나리오 사전 지정 REST 컨트롤러 (결제 4).
 *
 * <p>{@code @Profile("!prod")} + {@code @ConditionalOnProperty} 이중 방어로 프로덕션 프로파일
 * 에는 배포되지 않는다. 경로 prefix {@code /v1/dev/...} 로 관측 시 dev endpoint 임을 명확히.
 */
@RestController
@RequestMapping("/v1/dev/payment-scenarios")
@Profile("!prod")
@ConditionalOnProperty(
    prefix = "commerce.dev.payment-scenario",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PaymentScenarioController {

    private final DevPaymentScenarioRegistry registry;

    public PaymentScenarioController(DevPaymentScenarioRegistry registry) {
        this.registry = registry;
    }

    /**
     * 주문번호에 시나리오를 사전 지정한다. 결제 시작 요청에 scenario body 가 없을 때 이 값을
     * 사용한다.
     */
    @PutMapping("/{orderNumber}")
    public ResponseEntity<Void> setScenario(
        @PathVariable String orderNumber, @Valid @RequestBody SetPaymentScenarioRequest request) {
        registry.set(orderNumber, request.scenario());
        return ResponseEntity.noContent().build();
    }

    /**
     * 지정된 시나리오를 제거한다.
     */
    @DeleteMapping("/{orderNumber}")
    public ResponseEntity<Void> clearScenario(@PathVariable String orderNumber) {
        registry.clear(orderNumber);
        return ResponseEntity.noContent().build();
    }
}
