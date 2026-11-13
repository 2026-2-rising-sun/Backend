package com.shoppinglive.commercecore.order.controller;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.service.OrderSheetRefundService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/order-sheets/{number}/refunds")
@ConditionalOnProperty(prefix = "commercecore.refunds", name = "api-enabled", havingValue = "true")
public class OrderSheetRefundController {
    private final OrderSheetRefundService refunds;

    public OrderSheetRefundController(OrderSheetRefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderSheetRefundResponse>> request(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody OrderSheetRefundRequest request) {
        var result = refunds.request(member.memberId(), number, key, request);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .body(ApiResponse.ok(result.refund()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderSheetRefundResponse> get(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @PathVariable long id) {
        return ApiResponse.ok(refunds.get(member.memberId(), number, id));
    }
}
