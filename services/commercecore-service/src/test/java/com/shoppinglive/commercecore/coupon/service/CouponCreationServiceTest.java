package com.shoppinglive.commercecore.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class CouponCreationServiceTest {
    @Test
    @DisplayName("없거나 다른 판매자의 상품이면 DB에 쓰지 않는다")
    void missingAndForeignProductsNeverStartDatabaseWrites() {
        var jdbc = mock(JdbcTemplate.class);
        var products = mock(ProductLookup.class);
        var manager = mock(PlatformTransactionManager.class);
        var service =
                new CouponCreationService(
                        jdbc,
                        products,
                        manager,
                        org.mockito.Mockito.mock(
                                com.shoppinglive.commercecore.coupon.event
                                        .CouponDisplayEventPublisher.class));
        Instant start = Instant.parse("2026-10-09T00:00:00Z"), end = start.plusSeconds(60);
        when(products.findProduct(1L))
                .thenReturn(Optional.of(new ProductSnapshot(1L, "상품", null, "other")));
        assertThatThrownBy(() -> service.create("seller", "쿠폰", 1, 1, start, end, end, List.of(1L)))
                .isInstanceOf(BusinessException.class);
        when(products.findProduct(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create("seller", "쿠폰", 1, 1, start, end, end, List.of(1L)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc, manager);
    }
}
