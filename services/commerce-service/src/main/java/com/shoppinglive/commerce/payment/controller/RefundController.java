package com.shoppinglive.commerce.payment.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.payment.service.RefundIntakeService;
import com.shoppinglive.commerce.payment.service.RefundIntakeService.RefundView;
import com.shoppinglive.commerce.payment.service.RefundIntakeService.SellerRefundView;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
                    @Size(min = 1, max = 100, message = "크기는 {min}~{max} 범위여야 합니다.")
                    List<@Positive(message = "0보다 커야 합니다.") Long> cartItemIds) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("지원하지 않는 환불 요청 필드: " + name);
        }
    }

    @Operation(
            summary = "본인 결제 묶음 전체 또는 장바구니 항목 전체 수량 환불 접수",
            description =
                    "환불 API와 자동 실행·DB 복구는 기본 활성화된다. commerce.refunds.api-enabled=false로 경로를 비활성화하면 404이다. execution-enabled=false는 새 자동 실행·스캔을 중지하며 이미 접수한 요청은 DB에 보존되고 재활성화 시 복구한다.\n회원 본인 결제 묶음만 접수한다. 본문 생략은 결제 묶음 전체 환불, 본문을 보내면 비어 있지 않은 cartItemIds 배열이 필요하다. 값 지정은 해당 장바구니 항목 ID로 생성된 주문의 전체 수량을 뜻하며 빈 배열/null/빈 객체/알 수 없는 필드는 400으로 거절한다. 금액은 서버에 저장된 주문별 실부담액으로 계산하며 요청 금액/주문번호/부분 수량은 받지 않는다.\n결제 완료 후 168시간 경계 직전까지 서버 접수해야 한다. 장바구니 행은 결제 뒤 삭제되므로 주문 source_cart_item_id를 검증한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/v1/payment-groups/{number}/refunds")
    public ResponseEntity<ApiResponse<RefundView>> request(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) JsonNode body) {
        List<Long> cartItemIds = parseCartItemIds(body);
        RefundIntakeService.RequestResult result =
                refunds.request(member.memberId(), number, key, cartItemIds);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.refund()));
    }

    private List<Long> parseCartItemIds(JsonNode body) {
        if (body == null) {
            return List.of();
        }
        if (!body.isObject()) {
            throw invalidRefundBody();
        }
        try {
            RefundRequest request = mapper.treeToValue(body, RefundRequest.class);
            if (request.cartItemIds() == null || request.cartItemIds().isEmpty()) {
                throw invalidRefundBody();
            }
            return request.cartItemIds();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidRefundBody();
        }
    }

    private static BusinessException invalidRefundBody() {
        return new BusinessException(
                ErrorCode.INVALID_REQUEST, "환불 요청 본문에는 비어 있지 않은 cartItemIds 배열이 필요합니다.");
    }

    @Operation(summary = "본인 결제 묶음 환불 이력")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/payment-groups/{number}/refunds")
    public ApiResponse<List<RefundView>> list(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        return ApiResponse.ok(refunds.list(member.memberId(), number));
    }

    @Operation(summary = "본인 환불 요청 상세")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/payment-groups/{number}/refunds/{id}")
    public ApiResponse<RefundView> get(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @PathVariable long id) {
        return ApiResponse.ok(refunds.get(member.memberId(), number, id));
    }

    @Operation(
            summary = "본인 대상 주문의 환불 상태 목록",
            description = "혼합 판매자 요청에서 본인 대상 주문·실부담액만 반환한다. 결제 묶음 총액·다른 판매자 주문과 대상은 반환하지 않는다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/seller/refunds")
    public ApiResponse<List<SellerRefundView>> listSeller(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(refunds.listSeller(seller.memberId(), page, size));
    }

    @Operation(
            summary = "본인 대상 주문의 환불 상세",
            description = "요청의 판매자별 대상 금액과 주문만 반환하며 타 판매자 정보와 결제 묶음 총액은 숨긴다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/seller/refunds/{id}")
    public ApiResponse<SellerRefundView> getSeller(
            @AuthenticationPrincipal AuthenticatedUser seller, @PathVariable long id) {
        return ApiResponse.ok(refunds.getSeller(seller.memberId(), id));
    }
}
