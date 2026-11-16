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

    public InternalPaymentController(PaymentService payments, RefundIntakeService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @PostMapping("/payments")
    public PaymentExecution start(@Valid @RequestBody PaymentCommand command) {
        return payments.start(command);
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
