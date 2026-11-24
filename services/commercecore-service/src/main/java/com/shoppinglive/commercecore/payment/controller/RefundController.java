package com.shoppinglive.commercecore.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppinglive.commercecore.payment.dto.RefundView;
import com.shoppinglive.commercecore.payment.dto.RefundableTarget;
import com.shoppinglive.commercecore.payment.dto.SellerRefundView;
import com.shoppinglive.commercecore.payment.service.RefundQueryService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@ConditionalOnProperty(prefix = "commercecore.refunds", name = "api-enabled", havingValue = "true")
public class RefundController {
    private final com.shoppinglive.commercecore.payment.service.KafkaRefundService kafkaRefunds;
    private final RefundQueryService queries;

    public RefundController(
            RefundQueryService queries,
            com.shoppinglive.commercecore.payment.service.KafkaRefundService kafkaRefunds) {
        this.queries = queries;
        this.kafkaRefunds = kafkaRefunds;
    }

    @PostMapping("/v1/payment-groups/{number}/refunds")
    public ResponseEntity<ApiResponse<RefundView>> request(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) JsonNode body) {
        var selection = com.shoppinglive.commercecore.payment.dto.RefundSelection.parse(body);
        if (!kafkaRefunds.supports(member.memberId(), number))
            throw new BusinessException(ErrorCode.CONFLICT, "이전 정책 주문은 환불을 요청할 수 없습니다.");
        var result = kafkaRefunds.request(member.memberId(), number, key, selection);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.refund()));
    }

    @GetMapping("/v1/payment-groups/{number}/refundable-targets")
    public ApiResponse<List<RefundableTarget>> refundableTargets(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        return ApiResponse.ok(queries.refundableTargets(member.memberId(), number));
    }

    @GetMapping("/v1/payment-groups/{number}/refunds")
    public ApiResponse<List<RefundView>> list(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        return ApiResponse.ok(queries.list(member.memberId(), number));
    }

    @GetMapping("/v1/payment-groups/{number}/refunds/{id}")
    public ApiResponse<RefundView> get(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @PathVariable long id) {
        return ApiResponse.ok(queries.get(member.memberId(), number, id));
    }

    @GetMapping("/v1/seller/refunds")
    public ApiResponse<List<SellerRefundView>> listSeller(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queries.listSeller(seller.memberId(), page, size));
    }

    @GetMapping("/v1/seller/refunds/{id}")
    public ApiResponse<SellerRefundView> getSeller(
            @AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id) {
        return ApiResponse.ok(queries.getSeller(seller.memberId(), id));
    }
}
