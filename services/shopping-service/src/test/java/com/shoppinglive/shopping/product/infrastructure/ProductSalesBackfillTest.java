package com.shoppinglive.shopping.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shoppinglive.shopping.image.domain.ImageFormat;
import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import com.shoppinglive.shopping.sales.infrastructure.HttpSalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.ProductSalesRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "shopping.sales.backfill.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:product_sales_backfill;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class ProductSalesBackfillTest extends ShoppingSecuritySupport {

    private static final int CHUNK = 100;
    private static final int PRODUCTS = 250;
    private static final long PRICE = 100L;

    @Autowired ProductSalesBackfill backfill;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired ProductSalesRepository replica;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HttpSalesInfoClient commerce;

    @BeforeEach
    void commerceHasSalesForEveryRequestedProduct() {
        given(commerce.findByProductIds(anyCollection()))
            .willAnswer(invocation -> salesFor(invocation.getArgument(0)));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_sales");
        products.deleteAll();
        images.deleteAll();
    }

    @Test
    void backfillInsertsVersionZeroRowsAndSkipsProductsWithoutSales() {
        List<Long> ids = createProducts(2);
        long withSales = ids.get(0);
        given(commerce.findByProductIds(anyCollection())).willReturn(Map.of(withSales, sales(withSales, PRICE)));

        assertThat(backfill.backfill()).isEqualTo(1);
        assertThat(replica.findByProductIds(ids)).containsOnlyKeys(withSales);
        assertThat(replica.apply(sales(withSales, 200), 1)).isTrue();
        assertThat(replica.findByProductIds(List.of(withSales))).containsEntry(withSales, sales(withSales, 200));
    }

    @Test
    void secondRunInsertsNothing() {
        createProducts(PRODUCTS);

        assertThat(backfill.backfill()).isEqualTo(PRODUCTS);
        assertThat(backfill.backfill()).isZero();
    }

    @Test
    void backfillDoesNotOverwriteRowFromEvent() {
        long id = createProducts(1).getFirst();
        replica.apply(sales(id, 999), 1);

        assertThat(backfill.backfill()).isZero();
        assertThat(replica.findByProductIds(List.of(id))).containsEntry(id, sales(id, 999));
    }

    @Test
    void everyProductAcrossChunksIsCovered() {
        List<Long> ids = createProducts(PRODUCTS);

        assertThat(backfill.backfill()).isEqualTo(PRODUCTS);
        assertThat(replica.findByProductIds(ids)).hasSize(PRODUCTS);
        ArgumentCaptor<Collection<Long>> requests = ArgumentCaptor.forClass(Collection.class);
        verify(commerce, times(3)).findByProductIds(requests.capture());
        assertThat(requests.getAllValues().stream().map(Collection::size).toList()).containsExactly(CHUNK, CHUNK, 50);
    }

    @Test
    void unavailableCommerceOnLaterChunkStopsWithoutThrowing() {
        List<Long> ids = createProducts(PRODUCTS);
        AtomicInteger calls = new AtomicInteger();
        given(commerce.findByProductIds(anyCollection())).willAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new SalesInfoUnavailableException("commerce down");
            }
            return salesFor(invocation.getArgument(0));
        });

        assertThat(backfill.backfill()).isEqualTo(CHUNK);
        assertThat(replica.findByProductIds(ids.subList(0, CHUNK))).hasSize(CHUNK);
        assertThat(replica.findByProductIds(ids.subList(CHUNK, PRODUCTS))).isEmpty();
    }

    @Test
    void backfillBeanIsAbsentWhenPropertyIsUnset() {
        new ApplicationContextRunner()
            .withUserConfiguration(ProductSalesBackfill.class)
            .run(context -> assertThat(context).doesNotHaveBean(ProductSalesBackfill.class));
    }

    private List<Long> createProducts(int count) {
        long imageId = images.saveAndFlush(new ProductImage(UUID.randomUUID() + ".png", ImageFormat.PNG,
            100, 10, 10, "fixture.png")).getId();
        List<Product> saved = products.saveAll(IntStream.range(0, count)
            .mapToObj(i -> new Product("product-" + i, "description", imageId, "backfill-" + i))
            .toList());
        products.flush();
        return saved.stream().map(Product::getId).sorted().toList();
    }

    private static Map<Long, SalesInfo> salesFor(Collection<Long> productIds) {
        return productIds.stream().collect(Collectors.toMap(id -> id, id -> sales(id, PRICE)));
    }

    private static SalesInfo sales(long productId, long price) {
        return new SalesInfo(productId, 10L, price, SalesStatus.ON_SALE, 5);
    }
}
