package com.shoppinglive.commerce.order.controller;

import com.shoppinglive.commerce.order.dto.CartOrderCommand;
import com.shoppinglive.commerce.order.dto.OrderResponse;
import com.shoppinglive.commerce.order.entity.CartItem;
import com.shoppinglive.commerce.order.service.CartService;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/cart/items")
public class CartController {
    private final CartService cart;
    private final OrderCreationService orders;

    public CartController(CartService cart, OrderCreationService orders) {
        this.cart = cart;
        this.orders = orders;
    }

    @Operation(summary = "본인 장바구니 목록")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping
    public List<ItemResponse> list(@AuthenticationPrincipal AuthenticatedUser member) {
        return cart.list(member.memberId()).stream().map(ItemResponse::from).toList();
    }

    @Operation(summary = "장바구니 상품 추가", description = "상품당 한 항목. 중복 409. 담기는 재고 예약이나 가격 확정이 아니다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    public ResponseEntity<ItemResponse> add(
            @AuthenticationPrincipal AuthenticatedUser member,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.status(201)
                .body(
                        ItemResponse.from(
                                cart.add(
                                        member.memberId(),
                                        request.productId(),
                                        request.quantity())));
    }

    @Operation(summary = "본인 항목 절대 수량 변경")
    @SecurityRequirement(name = "BearerAuth")
    @PatchMapping("/{itemId}")
    public ItemResponse update(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable Long itemId,
            @Valid @RequestBody QuantityRequest request) {
        return ItemResponse.from(
                cart.changeQuantity(member.memberId(), itemId, request.quantity()));
    }

    @Operation(summary = "본인 항목 삭제")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable Long itemId) {
        cart.delete(member.memberId(), itemId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "장바구니 한 항목 주문",
            description =
                    "기존 한 항목 주문 경로를 유지하며 주문 하나를 가진 결제 묶음으로 생성한다. 회원당 활성 묶음 하나 제한은 단건에도 적용한다. 선택 항목의 상품·수량 변경과 현재 가격·판매·재고를 재검증하며 주문 저장·예약은 원자적이다. 생성 시 카트를 유지하고 결제 성공 때 원본 ID·버전이 같은 항목만 삭제한다. 대기 중 수량 변경·삭제는 허용하며 주문 스냅샷은 바뀌지 않는다. 동일 키·입력 재전송은 카트 삭제 뒤에도 원래 주문을 반환한다. 여러 항목은 POST /v1/cart/orders를 사용한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{itemId}/orders")
    public ResponseEntity<OrderResponse> order(
            @AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable Long itemId,
            @RequestHeader("X-Idempotency-Key") String key,
            @Valid @RequestBody CartOrderRequest request) {
        var result =
                orders.createFromCart(
                        member.memberId(),
                        itemId,
                        new CartOrderCommand(
                                request.buyerName(),
                                request.buyerPhone(),
                                request.expectedTotalAmount()),
                        key);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .body(OrderResponse.from(result.order()));
    }

    public record AddItemRequest(
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Long productId,
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Integer quantity) {}

    public record QuantityRequest(
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Integer quantity) {}

    public record CartOrderRequest(
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.")
                    String buyerName,
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Pattern(regexp = "^[0-9-]{9,32}$", message = "올바른 형식이어야 합니다.")
                    String buyerPhone,
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.")
                    Long expectedTotalAmount) {}

    public record ItemResponse(Long id, Long productId, Integer quantity, Long version) {
        static ItemResponse from(CartItem item) {
            return new ItemResponse(
                    item.getId(), item.getProductId(), item.getQuantity(), item.getVersion());
        }
    }
}
