package com.shoppinglive.payment.controller;

import com.shoppinglive.payment.dto.*;
import com.shoppinglive.payment.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/internal")
public class InternalPaymentController {
    private final PaymentService payments;
    private final RefundIntakeService refunds;
    private final OrderClosureService closures;

    public InternalPaymentController(
            PaymentService payments, RefundIntakeService refunds, OrderClosureService closures) {
        this.payments = payments;
        this.refunds = refunds;
        this.closures = closures;
    }

    @PostMapping("/payments")
    public PaymentExecution start(@Valid @RequestBody PaymentCommand command) {
        return payments.start(command);
    }

    @PutMapping("/payments/{paymentId}/order-closure")
    public OrderClosureService.Receipt closeOrder(
            @PathVariable long paymentId, @Valid @RequestBody OrderClosureCommand command) {
        return closures.close(paymentId, command);
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentExecution get(@PathVariable long paymentId) {
        return payments.get(paymentId);
    }

    @PostMapping("/refunds")
    public RefundExecution refund(@Valid @RequestBody RefundCommand command) {
        return refunds.start(command);
    }

    @GetMapping("/refunds/{executionId}")
    public RefundExecution refund(@PathVariable long executionId) {
        return refunds.get(executionId);
    }
}
