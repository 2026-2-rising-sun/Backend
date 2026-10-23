package com.shoppinglive.commerce.order.controller;

import com.shoppinglive.commerce.order.service.PaymentGroupService;
import com.shoppinglive.commerce.order.service.PaymentGroupService.Creation;
import com.shoppinglive.commerce.order.service.PaymentGroupService.GroupResponse;
import com.shoppinglive.commerce.order.service.PaymentGroupService.Quote;
import com.shoppinglive.commerce.order.service.PaymentGroupService.Selection;
import com.shoppinglive.commerce.payment.dto.PaymentAttemptResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentGroupController {
    private final PaymentGroupService service;

    public PaymentGroupController(PaymentGroupService service) {
        this.service = service;
    }

    public record PreviewRequest(
            @NotEmpty(message = "비어 있을 수 없습니다.")
                    @Size(max = 100, message = "크기는 {min}~{max} 범위여야 합니다.")
                    List<Selection> items,
            @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.") String couponId) {}

    public record CreateRequest(
            @NotEmpty(message = "비어 있을 수 없습니다.")
                    @Size(max = 100, message = "크기는 {min}~{max} 범위여야 합니다.")
                    List<Selection> items,
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.")
                    String buyerName,
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Pattern(regexp = "^[0-9-]{9,32}$", message = "올바른 형식이어야 합니다.")
                    String buyerPhone,
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.")
                    Long expectedTotalAmount,
            @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.") String couponId) {}

    @PostMapping("/v1/cart/checkout")
    public Quote quote(
            @AuthenticationPrincipal AuthenticatedUser member,
            @Valid @RequestBody PreviewRequest input) {
        return service.preview(member.memberId(), input.items(), input.couponId());
    }

    @PostMapping("/v1/cart/orders")
    public ResponseEntity<GroupResponse> create(
            @AuthenticationPrincipal AuthenticatedUser member,
            @RequestHeader("X-Idempotency-Key") String key,
            @Valid @RequestBody CreateRequest input) {
        Creation c =
                service.create(
                        member.memberId(),
                        input.items(),
                        input.buyerName(),
                        input.buyerPhone(),
                        input.expectedTotalAmount(),
                        input.couponId(),
                        key);
        return ResponseEntity.status(c.created() ? 201 : 200).body(c.group());
    }

    @GetMapping("/v1/payment-groups/active")
    public ResponseEntity<GroupResponse> active(@AuthenticationPrincipal AuthenticatedUser member) {
        return service.active(member.memberId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/v1/payment-groups/{number}")
    public GroupResponse get(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        return service.get(member.memberId(), number);
    }

    @PostMapping("/v1/payment-groups/{number}/payments")
    public ResponseEntity<PaymentAttemptResponse> pay(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @RequestHeader("X-Idempotency-Key") String key,
            @RequestBody(required = false) Map<String, Object> input) {
        if (input != null && !input.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 요청 본문은 비어 있어야 합니다.");
        }
        var attempt = service.start(member.memberId(), number, key);
        return ResponseEntity.status(attempt.getStatus().isTerminal() ? 200 : 202)
                .body(PaymentAttemptResponse.from(attempt));
    }

    @GetMapping("/v1/payment-groups/{number}/payments/{id}")
    public PaymentAttemptResponse payment(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @PathVariable Long id) {
        return PaymentAttemptResponse.from(service.payment(member.memberId(), number, id));
    }

    @PostMapping("/v1/payment-groups/{number}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        service.cancel(member.memberId(), number);
        return ResponseEntity.noContent().build();
    }
}
