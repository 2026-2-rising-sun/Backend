package com.shoppinglive.commerce.refunds.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService.RefundView;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService.SellerRefundView;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
@ConditionalOnProperty(prefix = "commerce.refunds", name = "api-enabled", havingValue = "true")
public class RefundController {
    private final RefundIntakeService refunds;
    private final ObjectMapper mapper;

    public RefundController(RefundIntakeService refunds, ObjectMapper mapper) {
        this.refunds = refunds;
        this.mapper = mapper;
    }

    public record RefundRequest(
        @JsonProperty(value = "cartItemIds", required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        @Size(max = 100) List<@Positive Long> cartItemIds) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("지원하지 않는 환불 요청 필드: " + name);
        }
    }

    @PostMapping("/v1/payment-groups/{number}/refunds")
    public ResponseEntity<ApiResponse<RefundView>> request(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable String number, @RequestHeader("Idempotency-Key") String key,
        @RequestBody(required = false) JsonNode body) {
        List<Long> cartItemIds = parseCartItemIds(body);
        RefundIntakeService.RequestResult result = refunds.request(member.memberId(), number, key, cartItemIds);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.ok(result.refund()));
    }

    private List<Long> parseCartItemIds(JsonNode body) {
        if (body == null) return List.of();
        if (!body.isObject()) throw invalidRefundBody();
        try {
            RefundRequest request = mapper.treeToValue(body, RefundRequest.class);
            return request.cartItemIds();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidRefundBody();
        }
    }

    private static BusinessException invalidRefundBody() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "환불 요청 본문은 cartItemIds 배열을 포함하는 객체여야 합니다.");
    }

    @GetMapping("/v1/payment-groups/{number}/refunds")
    public ApiResponse<List<RefundView>> list(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable String number) {
        return ApiResponse.ok(refunds.list(member.memberId(), number));
    }

    @GetMapping("/v1/payment-groups/{number}/refunds/{id}")
    public ApiResponse<RefundView> get(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable String number, @PathVariable long id) {
        return ApiResponse.ok(refunds.get(member.memberId(), number, id));
    }

    @GetMapping("/v1/seller/refunds")
    public ApiResponse<List<SellerRefundView>> listSeller(@AuthenticationPrincipal AuthenticatedUser seller,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(refunds.listSeller(seller.memberId(), page, size));
    }

    @GetMapping("/v1/seller/refunds/{id}")
    public ApiResponse<SellerRefundView> getSeller(@AuthenticationPrincipal AuthenticatedUser seller,
        @PathVariable long id) {
        return ApiResponse.ok(refunds.getSeller(seller.memberId(), id));
    }
}
