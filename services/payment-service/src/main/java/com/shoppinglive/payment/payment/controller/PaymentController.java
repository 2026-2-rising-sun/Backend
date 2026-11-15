package com.shoppinglive.payment.payment.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.payment.payment.dto.CheckoutResponse;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.CreatePaymentResponse;
import com.shoppinglive.payment.payment.dto.PaymentResponse;
import com.shoppinglive.payment.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Commerce가 X-Service-Token으로 부르는 내부 결제 API. */
@RestController
@RequestMapping("/v1/internal/payments")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /** 새 결제는 201, 같은 orderRef의 재요청은 200. */
    @PostMapping
    public ResponseEntity<ApiResponse<CreatePaymentResponse>> create(
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.Created created = service.create(request);
        return ResponseEntity.status(created.isNew() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(CreatePaymentResponse.from(created.payment())));
    }

    /** 부를 때마다 새 결제창이 생긴다(멱등 아님). */
    @PostMapping("/{orderRef}/checkouts")
    public ResponseEntity<ApiResponse<CheckoutResponse>> openCheckout(
            @PathVariable String orderRef) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(CheckoutResponse.from(service.openCheckout(orderRef))));
    }

    @GetMapping("/{orderRef}")
    public ApiResponse<PaymentResponse> get(@PathVariable String orderRef) {
        return ApiResponse.ok(PaymentResponse.from(service.get(orderRef)));
    }
}
