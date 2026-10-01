package com.shoppinglive.commerce.cart.api;

import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.cart.application.CartOrderCommand;
import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import com.shoppinglive.commerce.orders.api.OrderResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
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

    @GetMapping
    public List<ItemResponse> list(@AuthenticationPrincipal AuthenticatedUser member) {
        return cart.list(member.memberId()).stream().map(ItemResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ItemResponse> add(@AuthenticationPrincipal AuthenticatedUser member,
        @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.status(201).body(ItemResponse.from(
            cart.add(member.memberId(), request.productId(), request.quantity())));
    }

    @PatchMapping("/{itemId}")
    public ItemResponse update(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable Long itemId, @Valid @RequestBody QuantityRequest request) {
        return ItemResponse.from(cart.changeQuantity(member.memberId(), itemId, request.quantity()));
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable Long itemId) {
        cart.delete(member.memberId(), itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{itemId}/orders")
    public ResponseEntity<OrderResponse> order(@AuthenticationPrincipal AuthenticatedUser member,
        @PathVariable Long itemId, @RequestHeader("X-Idempotency-Key") String key,
        @Valid @RequestBody CartOrderRequest request) {
        var result = orders.createFromCart(member.memberId(), itemId,
            new CartOrderCommand(request.buyerName(), request.buyerPhone(), request.expectedTotalAmount()), key);
        return ResponseEntity.status(result.created() ? 201 : 200).body(OrderResponse.from(result.order()));
    }

    public record AddItemRequest(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {}
    public record QuantityRequest(@NotNull @Positive Integer quantity) {}
    public record CartOrderRequest(@NotBlank @Size(max = 64) String buyerName,
        @NotBlank @Pattern(regexp = "^[0-9-]{9,32}$") String buyerPhone,
        @NotNull @Positive Long expectedTotalAmount) {}
    public record ItemResponse(Long id, Long productId, Integer quantity, Long version) {
        static ItemResponse from(CartItem item) {
            return new ItemResponse(item.getId(), item.getProductId(), item.getQuantity(), item.getVersion());
        }
    }
}
