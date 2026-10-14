package com.shoppinglive.commerce.order.controller;

import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.dto.CreateOrderRequest;
import com.shoppinglive.commerce.order.dto.OrderCreationResult;
import com.shoppinglive.commerce.order.dto.OrderResponse;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 생성 API (주문 2).
 *
 * <p>조회·취소는 {@link OrderQueryController} 가 같은 {@code /v1/orders} 아래에서 담당한다. 생성만 따로 둔 이유는 의존하는 협력자가
 * 전혀 달라서다 (Shopping · 재고 · 주문번호 생성기).
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderCreationController {

    /** 재전송 중복 주문 방지용 클라이언트 키. 선택 항목이다. */
    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    private final OrderCreationService orderCreationService;

    public OrderCreationController(OrderCreationService orderCreationService) {
        this.orderCreationService = orderCreationService;
    }

    /**
     * 주문을 만들고 재고를 배정한다.
     *
     * <p>새로 만들어지면 201, {@code X-Idempotency-Key} 로 기존 주문을 그대로 돌려주면 200 이다. 두 경우 모두 본문은 같은 주문이라
     * 클라이언트는 구분하지 않아도 된다.
     *
     * <p>주요 실패: 판매 중이 아닌 상품·재고 부족·금액 변경은 409, 상품·판매정보 없음은 404, 입력 제약 위반은 400.
     */
    @Operation(
            summary = "회원 단건 주문 생성",
            description =
                    "couponId는 선택이며 지정 시 보유 쿠폰과 대상 상품을 검증해 할인액·실부담액을 저장하고 결제 묶음에서 예약한다. expectedTotalAmount는 할인 전 주문 총액이다. 서로 다른 회원은 같은 키를 독립 사용한다. 같은 회원의 키 재사용은 상품·수량·구매자 정보·expectedTotalAmount·couponId를 비교한다. 단건도 주문 하나를 가진 결제 묶음으로 생성하고 groupNumber를 반환한다. 회원당 활성 묶음은 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 다른 새 주문은 409, 동일 입력 재전송은 허용한다. 새 주문 저장·재고 예약·쿠폰 예약은 원자적이며 결제 전 기한은 15분이다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @AuthenticationPrincipal AuthenticatedUser member,
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        OrderCreationResult result =
                orderCreationService.create(
                        new CreateOrderCommand(
                                request.productId(),
                                request.quantity(),
                                request.buyerName(),
                                request.buyerPhone(),
                                member.memberId(),
                                request.expectedTotalAmount(),
                                null,
                                null,
                                request.couponId()),
                        idempotencyKey);

        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }
}
