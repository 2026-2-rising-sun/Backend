package com.shoppinglive.commercecore.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    @Mock CartItemRepository items;
    @Mock ProductLookup products;
    @Mock SkuService skus;
    private CartService cart;

    @BeforeEach
    void setUp() {
        cart = new CartService(items, products, skus);
    }

    @Test
    @DisplayName("같은 상품의 서로 다른 SKU를 한 상품 아래 개별 수량과 현재가로 조회한다")
    void groupsDifferentSkusWithoutMergingQuantities() {
        when(items.findByMemberIdOrderByIdDesc(MEMBER))
                .thenReturn(List.of(item(2L, 501L, 102L, 5), item(1L, 501L, 101L, 2)));
        when(skus.findBySkuIds(List.of(102L, 101L)))
                .thenReturn(List.of(sku(101L, 501L, 1000, 10), sku(102L, 501L, 2000, 10)));
        when(products.findProducts(List.of(501L))).thenReturn(List.of(product(501L, true)));

        var groups = cart.listGrouped(MEMBER);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().productId()).isEqualTo(501L);
        assertThat(groups.getFirst().items())
                .extracting(value -> value.skuId())
                .containsExactly(102L, 101L);
        assertThat(groups.getFirst().items())
                .extracting(value -> value.quantity())
                .containsExactly(5, 2);
        assertThat(groups.getFirst().items())
                .extracting(value -> value.unitPrice())
                .containsExactly(2000L, 1000L);
        assertThat(groups.getFirst().items()).allMatch(value -> value.purchasable());
        verify(skus, never()).reserve(anyLong(), anyInt());
        verify(skus, never()).lockForOrder(any());
    }

    @Test
    @DisplayName("SKU가 판매 중이어도 상품 전체 판매가 꺼져 있으면 구매 불가로 표시한다")
    void respectsProductSaleSwitch() {
        when(items.findByMemberIdOrderByIdDesc(MEMBER))
                .thenReturn(List.of(item(1L, 501L, 101L, 2)));
        when(skus.findBySkuIds(List.of(101L))).thenReturn(List.of(sku(101L, 501L, 1000, 10)));
        when(products.findProducts(List.of(501L))).thenReturn(List.of(product(501L, false)));

        var group = cart.listGrouped(MEMBER).getFirst();

        assertThat(group.saleEnabled()).isFalse();
        assertThat(group.items().getFirst().purchasable()).isFalse();
        assertThat(group.items().getFirst().unitPrice()).isEqualTo(1000);
    }

    @Test
    @DisplayName("담긴 수량보다 재고가 적거나 품절이면 수량을 바꾸지 않고 구매 불가로 표시한다")
    void preservesQuantityWhenAvailableStockIsInsufficient() {
        when(items.findByMemberIdOrderByIdDesc(MEMBER))
                .thenReturn(List.of(item(1L, 501L, 101L, 5), item(2L, 501L, 102L, 2)));
        when(skus.findBySkuIds(List.of(101L, 102L)))
                .thenReturn(List.of(sku(101L, 501L, 1000, 3), sku(102L, 501L, 1000, 0)));
        when(products.findProducts(List.of(501L))).thenReturn(List.of(product(501L, true)));

        var values = cart.listGrouped(MEMBER).getFirst().items();

        assertThat(values).extracting(value -> value.quantity()).containsExactly(5, 2);
        assertThat(values).noneMatch(value -> value.purchasable());
        assertThat(values.getLast().skuStatus()).isEqualTo("SOLD_OUT");
        verify(skus, never()).reserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("SKU 미매핑·삭제된 상품 항목은 원래 수량을 보존하고 이용 불가로 조회한다")
    void keepsUnavailableLegacyItemVisible() {
        CartItem legacy = new CartItem(MEMBER, 999L, 8);
        ReflectionTestUtils.setField(legacy, "id", 1L);
        ReflectionTestUtils.setField(legacy, "version", 0L);
        when(items.findByMemberIdOrderByIdDesc(MEMBER)).thenReturn(List.of(legacy));
        when(products.findProducts(List.of(999L))).thenReturn(List.of());

        var group = cart.listGrouped(MEMBER).getFirst();

        assertThat(group.productId()).isEqualTo(999L);
        assertThat(group.productName()).isNull();
        assertThat(group.items().getFirst().quantity()).isEqualTo(8);
        assertThat(group.items().getFirst().skuId()).isNull();
        assertThat(group.items().getFirst().unitPrice()).isNull();
        assertThat(group.items().getFirst().purchasable()).isFalse();
        verifyNoInteractions(skus);
        verify(items, never()).delete(any());
    }

    @Test
    @DisplayName("SKU 담기는 실제 상품 참조로 저장하고 재고를 예약하지 않는다")
    void addsSkuUsingItsActualProductReferenceWithoutReservation() {
        when(skus.findBySkuIds(List.of(101L))).thenReturn(List.of(sku(101L, 501L, 1000, 10)));
        when(products.findProduct(501L)).thenReturn(java.util.Optional.of(product(501L, false)));
        when(items.saveAndFlush(any(CartItem.class))).thenAnswer(call -> call.getArgument(0));

        CartItem added = cart.addSku(MEMBER, 101L, 2);

        assertThat(added.getSkuId()).isEqualTo(101L);
        assertThat(added.getProductId()).isEqualTo(501L);
        assertThat(added.getQuantity()).isEqualTo(2);
        verify(skus, never()).reserve(anyLong(), anyInt());
        verify(skus, never()).lockForOrder(any());
    }

    @Test
    @DisplayName("같은 SKU를 다시 담으면 기존 수량을 누적하지 않고 충돌을 반환한다")
    void rejectsDuplicateSkuWithoutChangingQuantity() {
        when(skus.findBySkuIds(List.of(101L))).thenReturn(List.of(sku(101L, 501L, 1000, 10)));
        when(products.findProduct(501L)).thenReturn(java.util.Optional.of(product(501L, true)));
        when(items.existsByMemberIdAndSkuId(MEMBER, 101L)).thenReturn(true);

        assertThatThrownBy(() -> cart.addSku(MEMBER, 101L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미");
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("기존 상품 ID 담기는 여러 SKU 중 임의 선택하지 않고 명시적 SKU 지정을 요구한다")
    void rejectsAmbiguousLegacyProductRequest() {
        when(products.findProduct(501L)).thenReturn(java.util.Optional.of(product(501L, true)));
        when(skus.findByProductId(501L))
                .thenReturn(List.of(sku(101L, 501L, 1000, 10), sku(102L, 501L, 2000, 10)));

        assertThatThrownBy(() -> cart.add(MEMBER, 501L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SKU");
        verify(items, never()).saveAndFlush(any());
    }

    private CartItem item(Long id, Long productId, Long skuId, int quantity) {
        CartItem item = new CartItem(MEMBER, productId, skuId, quantity);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "version", 0L);
        return item;
    }

    private SkuView sku(Long id, Long productId, long price, int available) {
        return new SkuView(id, productId, price, available, 0, SkuSaleStatus.ON_SALE, 0);
    }

    private ProductSnapshot product(Long id, boolean saleEnabled) {
        return new ProductSnapshot(id, "상품 " + id, null, "seller", saleEnabled);
    }
}
