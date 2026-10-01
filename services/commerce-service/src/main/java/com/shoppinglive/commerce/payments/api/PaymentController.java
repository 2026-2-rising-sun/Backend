package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.Map;
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
        @AuthenticationPrincipal AuthenticatedUser member,
        @RequestBody(required = false) Map<String, Object> request) {
        if (request != null && !request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 요청에는 결과 선택 필드를 보낼 수 없습니다.");
        }
        PaymentAttempt attempt = paymentService.startPayment(orderNumber, member.memberId());
        return PaymentAttemptResponse.from(attempt);
    }

    /**
     * 결제 시도를 조회한다 (결제 3). 지연·응답 유실 후 재확인 용도.
     */
    @GetMapping("/{paymentId}")
    public PaymentAttemptResponse getPayment(
        @PathVariable String orderNumber,
        @PathVariable Long paymentId,
        @AuthenticationPrincipal AuthenticatedUser member) {
        PaymentAttempt attempt = paymentService.getPayment(orderNumber, member.memberId(), paymentId);
        return PaymentAttemptResponse.from(attempt);
    }
}
