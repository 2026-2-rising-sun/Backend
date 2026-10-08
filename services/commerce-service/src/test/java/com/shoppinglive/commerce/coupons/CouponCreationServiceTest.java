package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.shoppinglive.commerce.coupons.application.CouponCreationService;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class CouponCreationServiceTest {
    @Test
    void missingAndForeignProductsNeverStartDatabaseWrites() {
        var jdbc=mock(JdbcTemplate.class);
        var shopping=mock(ShoppingClient.class);
        var manager=mock(PlatformTransactionManager.class);
        var service=new CouponCreationService(jdbc,shopping,manager);
        Instant start=Instant.parse("2026-10-09T00:00:00Z"),end=start.plusSeconds(60);
        when(shopping.findProduct(1L)).thenReturn(Optional.of(new ProductSnapshot(1L,"상품",null,"other")));
        assertThatThrownBy(() -> service.create("seller","쿠폰",1,1,start,end,end,List.of(1L)))
            .isInstanceOf(BusinessException.class);
        when(shopping.findProduct(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create("seller","쿠폰",1,1,start,end,end,List.of(1L)))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc,manager);
    }
}
