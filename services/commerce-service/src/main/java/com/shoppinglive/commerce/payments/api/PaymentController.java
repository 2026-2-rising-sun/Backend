package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 REST 컨트롤러.
 *
 * <p>경로: {@code /v1/orders/{orderNumber}/payments}. Infra Ingress 가
 * {@code /api/commerce/} prefix 제거.
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

    /**
     * 결제 시도를 조회한다 (결제 3). 지연·응답 유실 후 재확인 용도.
     */
    @GetMapping("/{paymentId}")
    public PaymentAttemptResponse getPayment(
        @PathVariable String orderNumber,
        @PathVariable Long paymentId,
        @RequestHeader("X-Order-Password") String password) {
        PaymentAttempt attempt = paymentService.getPayment(orderNumber, password, paymentId);
        return PaymentAttemptResponse.from(attempt);
    }
}
