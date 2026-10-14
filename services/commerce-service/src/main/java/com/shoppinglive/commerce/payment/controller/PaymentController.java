package com.shoppinglive.commerce.payment.controller;

import com.shoppinglive.commerce.payment.dto.PaymentAttemptResponse;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.service.PaymentService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 REST 컨트롤러.
 *
 * <p>경로: {@code /v1/orders/{orderNumber}/payments}. Infra Ingress 가 {@code /api/commerce/} prefix
 * 제거.
 */
@RestController
@RequestMapping("/v1/orders/{orderNumber}/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 결제를 시작한다 (결제 1). */
    @Operation(
            summary = "본인 주문 결제 시작",
            description =
                    "결제 결과는 Mock gateway가 결정한다. 단건 묶음은 이 경로로 결제하고 기존 시도를 재전송에 반환한다. 여러 주문이 연결된 묶음의 개별 결제는 409이며 통합 결제 API를 사용한다. 응답은 요청 접수 시점 상태이며 이후 조회로 확인한다.")
    @SecurityRequirement(name = "BearerAuth")
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

    /** 결제 시도를 조회한다 (결제 3). 지연·응답 유실 후 재확인 용도. */
    @Operation(summary = "본인 주문의 결제 시도 조회", description = "다른 회원 주문 또는 다른 주문의 paymentId는 모두 404.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{paymentId}")
    public PaymentAttemptResponse getPayment(
            @PathVariable String orderNumber,
            @PathVariable Long paymentId,
            @AuthenticationPrincipal AuthenticatedUser member) {
        PaymentAttempt attempt =
                paymentService.getPayment(orderNumber, member.memberId(), paymentId);
        return PaymentAttemptResponse.from(attempt);
    }
}
