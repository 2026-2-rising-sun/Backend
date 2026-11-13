package com.shoppinglive.commercecore.order.controller;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.service.*;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.service.PaymentDispatcher;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/order-sheets")
public class OrderSheetController {
    private final OrderSheetService sheets;
    private final OrderSheetLifecycleService lifecycle;
    private final PaymentDispatcher dispatcher;

    public OrderSheetController(
            OrderSheetService sheets,
            OrderSheetLifecycleService lifecycle,
            PaymentDispatcher dispatcher) {
        this.sheets = sheets;
        this.lifecycle = lifecycle;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<OrderSheetResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateOrderSheetRequest request) {
        var created = sheets.create(user.memberId(), key, request);
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.order());
    }

    @GetMapping("/{number}")
    public OrderSheetResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        return sheets.get(user.memberId(), number);
    }

    @PostMapping("/{number}/payments")
    public PaymentExecution pay(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String number,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String key,
            @Valid @RequestBody PaymentRequest request) {
        var accepted =
                lifecycle.start(
                        user.memberId(),
                        number,
                        key,
                        request.expectedPayableAmount(),
                        request.couponId(),
                        request.couponOrderItemId());
        return dispatcher.paymentAfterCommit(accepted.paymentId());
    }

    @PostMapping("/{number}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        lifecycle.cancel(user.memberId(), number);
        return ResponseEntity.noContent().build();
    }

    public record PaymentRequest(
            @jakarta.validation.constraints.NotNull @PositiveOrZero Long expectedPayableAmount,
            @jakarta.validation.constraints.Size(max = 64) String couponId,
            @jakarta.validation.constraints.Positive Long couponOrderItemId) {}
}
