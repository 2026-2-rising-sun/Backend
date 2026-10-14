package com.shoppinglive.commerce.order.controller;

import com.shoppinglive.commerce.order.service.PaymentGroupService;
import com.shoppinglive.commerce.order.service.PaymentGroupService.*;
import com.shoppinglive.commerce.payment.dto.PaymentAttemptResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @Operation(
            summary = "선택 상품 통합 주문서 확인",
            description =
                    "1~100개 항목의 소유권·버전·판매 상태·재고와 최신 가격을 확인한다. couponId가 있으면 회원 보유·사용 가능 기간과 대상 상품을 서버에서 확인하고 대상 주문에만 정액 할인을 비례 배분한다. 중복 ID는 400, 변경된 버전·판매 불가·재고 부족·사용할 수 없는 쿠폰은 409. 조회 단계는 쿠폰·재고를 예약하거나 주문을 만들지 않는다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/v1/cart/checkout")
    public Quote quote(
            @AuthenticationPrincipal AuthenticatedUser member,
            @Valid @RequestBody PreviewRequest input) {
        return service.preview(member.memberId(), input.items(), input.couponId());
    }

    @Operation(
            summary = "상품별 주문과 결제 묶음 생성",
            description =
                    "선택 상품별 주문을 생성하고 하나의 결제 묶음에 연결한다. couponId는 선택이며 지정 시 회원 쿠폰을 주문 생성 트랜잭션에서 예약하고, 할인액·대상 주문별 배분·결제 실부담액을 스냅샷으로 저장한다. expectedTotalAmount는 할인 전 총액이다. 회원당 활성 묶음은 단건 구매를 포함해 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 새로운 구매는 409. 회원별 동일 키·정렬된 항목 ID/버전·구매자·기대 총액·쿠폰 ID가 같은 요청은 기존 묶음을 200으로 반환하고 다른 입력은 409. 카트 잠금·버전/가격/상태 재검증·쿠폰 예약·전체 주문 저장·재고 예약은 한 트랜잭션이며 하나라도 실패하면 전부 롤백한다. 카트는 생성 시 삭제하지 않으며 편집할 수 있다. 성공 때 원본 itemId/version이 같은 항목만 삭제하고 이후 변경·재등록된 항목은 유지한다. 미결제 기한은 15분이며 실패/취소/만료 뒤에는 새 묶음을 만들어야 한다.")
    @SecurityRequirement(name = "BearerAuth")
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

    @Operation(
            summary = "본인의 활성 결제 묶음 조회",
            description =
                    "회원의 PENDING_PAYMENT 또는 PAYMENT_CONFIRMING 묶음 하나를 반환한다. 생성·결제 응답 유실 또는 새로고침 후 결과 복구에 사용한다. 활성 묶음이 없으면 204이며 실패·취소·만료·완료 묶음은 활성 조회에서 제외한다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/payment-groups/active")
    public ResponseEntity<GroupResponse> active(@AuthenticationPrincipal AuthenticatedUser member) {
        return service.active(member.memberId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "본인 결제 묶음과 상품별 주문 조회",
            description =
                    "다른 회원 묶음과 없는 묶음은 동일 404. 합계와 상품별 주문 목록 및 결제 시도 ID를 반환한다. 각 주문은 내 주문 목록·상세에서 별도로 조회된다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/payment-groups/{number}")
    public GroupResponse get(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        return service.get(member.memberId(), number);
    }

    @Operation(
            summary = "통합 결제 시작",
            description =
                    "양수 결제 금액은 Mock gateway 승인을 한 번 요청한다. 결제 금액이 0원이면 금전 승인을 요청하지 않고 주문·재고·쿠폰을 동기 확정하며 응답 시 결제 시도와 묶음 상태가 SUCCESS/PAID다. 같은 X-Idempotency-Key 재전송은 기존 결제 시도를 반환한다. 이미 시작된 결제에 다른 키를 보내면 409. 결제 전 만료시각을 검사하며 기한이 지난 묶음은 시작할 수 없다. 양수 승인 결과 불명/응답 유실은 확인 중으로 유지하고 조회·재확인으로 복구한다. Mock 승인 결과는 주문 반영 트랜잭션과 별도로 보관하여 승인 뒤 반영 실패에도 같은 시도를 재확인한다. 실제 외부 PG 승인을 보장하는 구현은 아니다.")
    @SecurityRequirement(name = "BearerAuth")
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

    @Operation(
            summary = "통합 결제 시도 결과 조회",
            description = "본인 묶음에 연결된 결제 시도만 조회한다. 타인 묶음·다른 묶음의 시도·없는 시도는 동일 404.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/payment-groups/{number}/payments/{id}")
    public PaymentAttemptResponse payment(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String number,
            @PathVariable Long id) {
        return PaymentAttemptResponse.from(service.payment(member.memberId(), number, id));
    }

    @Operation(
            summary = "결제 전 전체 주문 취소",
            description =
                    "PENDING_PAYMENT 묶음의 상품별 주문 전체를 취소하고 예약 재고를 한 트랜잭션에서 반환한다. CANCELLED 재전송은 204. 결제 확인 중·완료·실패·만료는 409. 부분 취소/환불은 지원하지 않는다. 카트는 삭제하지 않는다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/v1/payment-groups/{number}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String number) {
        service.cancel(member.memberId(), number);
        return ResponseEntity.noContent().build();
    }
}
