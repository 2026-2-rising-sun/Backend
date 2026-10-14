package com.shoppinglive.shopping.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.shopping.product.entity.Product;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.storage.ImageUrlResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class ProductSellerQueryTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ImageUrlResolver images = mock(ImageUrlResolver.class);
    private final ProductQueryService query = new ProductQueryService(products, images);

    @Test
    @DisplayName("인증된 판매자의 상품을 일관된 페이지와 스냅샷으로 조회한다")
    void queryUsesAuthenticatedOwnerWithStablePagingAndSnapshot() {
        var page =
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(products.findBySellerId("seller-a", page))
                .thenReturn(
                        new PageImpl<>(
                                List.of(new Product("owned", "owned", 1L, "key", "seller-a")),
                                page,
                                1));
        when(images.urlOf(1L)).thenReturn("/image/1");
        var result = query.findOwnedProducts("seller-a", 0, 20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().sellerId()).isEqualTo("seller-a");
        assertThat(result.items().getFirst().mainImageUrl()).isEqualTo("/image/1");
        verify(products).findBySellerId("seller-a", page);
    }

    @Test
    @DisplayName("잘못된 페이지 요청은 DB 조회 전에 거절한다")
    void invalidPagingDoesNotQueryDatabase() {
        assertThatThrownBy(() -> query.findOwnedProducts("seller", -1, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.findOwnedProducts("seller", 0, 101))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.findOwnedProducts("seller", Integer.MAX_VALUE, 100))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(products);
    }
}
