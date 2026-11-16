package com.shoppinglive.commercecore.payment.relay;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record RelayOrderRequest(
        List<@NotNull @Valid Item> items,
        List<@NotNull @Valid CartSelection> cartItems,
        @NotBlank(message = "구매자 이름이 필요합니다.") @Size(max = 64) String buyerName,
        @NotBlank(message = "구매자 연락처가 필요합니다.") @Size(max = 32) String buyerPhone,
        @NotNull(message = "예상 실결제액이 필요합니다.") @Positive(message = "실결제액은 양수여야 합니다.")
                Long expectedAmount,
        @Size(max = 64) String couponId,
        @Positive Long couponSkuId) {
    public CreateOrderSheetRequest sheet() {
        return new CreateOrderSheetRequest(items, cartItems, buyerName, buyerPhone);
    }
}
