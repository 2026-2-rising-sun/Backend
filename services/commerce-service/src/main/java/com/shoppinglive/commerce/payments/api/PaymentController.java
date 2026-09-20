package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 REST 컨트롤러.
 *
 * <p>경로: {@code /v1/orders/{orderNumber}/payments}. 컨트롤러 경로는 {@code /v1/...} 로
 * 시작. Infra Ingress 가 {@code /api/commerce/} prefix 제거.
 */
@RestController
@RequestMapping("/v1/orders/{orderNumber}/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 결제를 시작한다 (결제 1).
     *
     * <p>{@code scenario} body optional (INSTANT_SUCCESS default). Sprint 3 에서 프로덕션
     * 프로파일에서는 이 필드 격리 예정.
     */
    @PostMapping
    public PaymentAttemptResponse startPayment(
        @PathVariable String orderNumber,
        @RequestHeader("X-Order-Password") String password,
        @RequestBody(required = false) StartPaymentRequest request) {
        PaymentScenario scenario = request != null ? request.scenario() : null;
        PaymentAttempt attempt = paymentService.startPayment(orderNumber, password, scenario);
        return PaymentAttemptResponse.from(attempt);
    }
}
