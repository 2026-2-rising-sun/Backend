package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.commerce.orders.application.CreateOrderCommand;
import com.shoppinglive.commerce.orders.application.OrderCreationResult;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 생성 API (주문 2).
 *
 * <p>조회·취소는 {@link OrderQueryController} 가 같은 {@code /v1/orders} 아래에서 담당한다.
 * 생성만 따로 둔 이유는 의존하는 협력자가 전혀 달라서다 (Shopping · 재고 · 주문번호 생성기).
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
     * <p>새로 만들어지면 201, {@code X-Idempotency-Key} 로 기존 주문을 그대로 돌려주면 200 이다.
     * 두 경우 모두 본문은 같은 주문이라 클라이언트는 구분하지 않아도 된다.
     *
     * <p>주요 실패: 판매 중이 아닌 상품·재고 부족·금액 변경은 409, 상품·판매정보 없음은 404,
     * 입력 제약 위반은 400.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
        @Valid @RequestBody CreateOrderRequest request,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        OrderCreationResult result = orderCreationService.create(
            new CreateOrderCommand(
                request.productId(),
                request.quantity(),
                request.buyerName(),
                request.buyerPhone(),
                request.lookupPassword(),
                request.expectedTotalAmount()),
            idempotencyKey);

        return ResponseEntity
            .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(OrderResponse.from(result.order()));
    }
}
