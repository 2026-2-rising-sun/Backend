package com.shoppinglive.shopping.product;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.application.ProductQueryService;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductSellerQueryTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ImageUrlResolver images = mock(ImageUrlResolver.class);
    private final ProductQueryService query = new ProductQueryService(products, images);

    @Test
    void queryUsesAuthenticatedOwnerWithStablePagingAndSnapshot() {
        var page = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(products.findBySellerId("seller-a", page)).thenReturn(new PageImpl<>(
            List.of(new Product("owned", "owned", 1L, "key", "seller-a")), page, 1));
        when(images.urlOf(1L)).thenReturn("/image/1");
        var result = query.findOwnedProducts("seller-a", 0, 20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().sellerId()).isEqualTo("seller-a");
        assertThat(result.items().getFirst().mainImageUrl()).isEqualTo("/image/1");
        verify(products).findBySellerId("seller-a", page);
    }

    @Test
    void invalidPagingDoesNotQueryDatabase() {
        assertThatThrownBy(() -> query.findOwnedProducts("seller", -1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.findOwnedProducts("seller", 0, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.findOwnedProducts("seller", Integer.MAX_VALUE, 100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(products);
    }
}
