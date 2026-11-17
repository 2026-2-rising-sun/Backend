package com.shoppinglive.commercecore.payment.relay;

import com.shoppinglive.commercecore.order.service.OrderSheetLifecycleService;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/order-sheets")
public class OrderPaymentRelayController {
    private final PaymentRelayService service;
    private final PaymentRelayDispatcher dispatcher;
    private final OrderSheetLifecycleService lifecycle;

    public OrderPaymentRelayController(
            PaymentRelayService service,
            PaymentRelayDispatcher dispatcher,
            OrderSheetLifecycleService lifecycle) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
    }

    @PostMapping
    public ResponseEntity<PaymentRelayService.View> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("X-Idempotency-Key") String key,
            @Valid @RequestBody RelayOrderRequest request) {
        var created = service.create(user.memberId(), key, request);
        if ("PENDING".equals(service.state(user.memberId(), created.number()).orderStatus()))
            dispatcher.dispatch(service.prepare(user.memberId(), created.number()));
        return ResponseEntity.status(created.created() ? 201 : 200)
                .body(service.view(user.memberId(), created.number()));
    }

    @GetMapping("/active")
    public ResponseEntity<PaymentRelayService.View> active(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.active(user.memberId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{number}")
    public PaymentRelayService.View get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        return service.view(user.memberId(), number);
    }

    @PostMapping("/{number}/payment")
    public PaymentRelayService.PaymentState prepare(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        dispatcher.dispatch(service.prepare(user.memberId(), number));
        return service.state(user.memberId(), number);
    }

    @PostMapping("/{number}/payment/checkouts")
    public PaymentRelayService.PaymentState checkout(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        return dispatcher.checkout(user.memberId(), number);
    }

    public record ApprovalInput(
            @NotBlank @Size(max = 200) String paymentKey,
            @NotBlank @Size(max = 64) String pgOrderId,
            @NotNull @Positive Long amount) {}

    @PostMapping("/{number}/payment/approval")
    public ResponseEntity<PaymentRelayService.PaymentState> approve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String number,
            @RequestHeader("X-Idempotency-Key") String key,
            @Valid @RequestBody ApprovalInput input) {
        var request =
                new PaymentRelayContract.Approval(
                        user.memberId(), input.paymentKey(), input.pgOrderId(), input.amount());
        dispatcher.dispatch(service.acceptApproval(user.memberId(), number, key, request));
        var state = service.state(user.memberId(), number);
        return "PROCESSING".equals(state.orderStatus())
                ? ResponseEntity.accepted()
                        .location(java.net.URI.create("/v1/order-sheets/" + number))
                        .body(state)
                : ResponseEntity.ok(state);
    }

    @PostMapping("/{number}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String number) {
        service.owned(user.memberId(), number);
        lifecycle.cancel(user.memberId(), number);
        return ResponseEntity.noContent().build();
    }
}
