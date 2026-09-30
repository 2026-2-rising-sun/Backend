package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.application.DevPaymentScenarioRegistry;
import jakarta.validation.Valid;
import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 명시적으로 켠 local/test 환경에서만 제공하는 Mock 결제 결과 제어. */
@RestController
@RequestMapping("/v1/dev/payment-scenarios")
@Profile("(local | test) & !dev & !prod")
@ConditionalOnProperty(
    prefix = "commerce.dev.payment-scenario",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class PaymentScenarioController {

    private final DevPaymentScenarioRegistry registry;
    private final OrderService orders;

    public PaymentScenarioController(DevPaymentScenarioRegistry registry, OrderService orders) {
        this.registry = registry;
        this.orders = orders;
    }

    /**
     * 주문번호에 시나리오를 사전 지정한다. 결제 시작 요청에 scenario body 가 없을 때 이 값을
     * 사용한다.
     */
    @PutMapping("/{orderNumber}")
    public ResponseEntity<Void> setScenario(
        @PathVariable String orderNumber, @Valid @RequestBody SetPaymentScenarioRequest request,
        @AuthenticationPrincipal AuthenticatedUser member) {
        orders.findByOrderNumberAndMemberId(orderNumber, member.memberId());
        registry.set(orderNumber, request.scenario());
        return ResponseEntity.noContent().build();
    }

    /**
     * 지정된 시나리오를 제거한다.
     */
    @DeleteMapping("/{orderNumber}")
    public ResponseEntity<Void> clearScenario(@PathVariable String orderNumber,
        @AuthenticationPrincipal AuthenticatedUser member) {
        orders.findByOrderNumberAndMemberId(orderNumber, member.memberId());
        registry.clear(orderNumber);
        return ResponseEntity.noContent().build();
    }
}
