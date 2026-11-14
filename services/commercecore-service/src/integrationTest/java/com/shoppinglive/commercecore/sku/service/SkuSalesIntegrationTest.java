package com.shoppinglive.commercecore.sku.service;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.product.service.ProductLifecycleService;
import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.product.service.SellerProductQueryService;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = com.shoppinglive.commercecore.product.support.ProductHttpTestApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:sku_sales;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "shoppinglive.outbox.relay.enabled=false"
        })
class SkuSalesIntegrationTest
        extends com.shoppinglive.commercecore.product.support.ProductSecuritySupport {
    @Autowired SkuSalesService sales;
    @Autowired SkuService skus;
    @Autowired ProductRepository products;
    @Autowired ProductLifecycleService lifecycle;
    @Autowired ProductTradingService trading;
    @Autowired SellerProductQueryService queries;
    @Autowired OutboxMessageRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    Product product;

    @BeforeEach
    void prepare() {
        product =
                products.saveAndFlush(
                        new Product("상품", "설명", 1L, UUID.randomUUID().toString(), "seller"));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sku");
        events.deleteAll();
        products.deleteAll();
    }

    SkuView register(int stock) {
        return sales.register(product.getId(), 1000, stock, "seller");
    }

    @Test
    @DisplayName("기존 판매 등록은 SKU와 Outbox만 만들고 상품 중지 중에도 준비한다")
    void registrationWritesOnlySkuAndOutbox() {
        var sku = register(0);
        assertThat(sku.status()).isEqualTo(SalesStatus.READY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_info", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_stock", Long.class)).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThatThrownBy(() -> register(1))
                .isInstanceOf(
                        com.shoppinglive.commercecore.sales.exception
                                .SalesAlreadyRegisteredException.class);
        assertThat(skus.findByProductId(product.getId())).hasSize(1);
    }

    @Test
    @DisplayName("판매 준비·변경은 본인과 미삭제를 검사하며 실패하면 SKU와 이벤트를 보존한다")
    void rejectsOtherSellerAndDeletedProduct() {
        assertThatThrownBy(() -> sales.register(product.getId(), 1000, 1, "other"))
                .isInstanceOf(BusinessException.class);
        var sku = register(2);
        assertThatThrownBy(() -> sales.changePrice(sku.skuId(), 2000, "other"))
                .isInstanceOf(BusinessException.class);
        lifecycle.delete(product.getId(), 0L, "seller");
        assertThatThrownBy(() -> sales.adjustAvailable(sku.skuId(), 1, "seller"))
                .isInstanceOf(BusinessException.class);
        assertThat(skus.findBySkuIds(List.of(sku.skuId())).getFirst().price()).isEqualTo(1000);
    }

    @Test
    @DisplayName("0재고 판매 시작은 품절로 파생되고 가격·재고 변경은 상품 조회에 즉시 반영된다")
    void singleQueriesReadCurrentSkuAndDerivedStatus() {
        var sku = register(0);
        assertThat(sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller").status())
                .isEqualTo(SalesStatus.SOLD_OUT);
        sales.changePrice(sku.skuId(), 3000, "seller");
        sales.adjustAvailable(sku.skuId(), 2, "seller");
        var detail = queries.get(product.getId(), "seller");
        assertThat(detail.price()).isEqualTo(3000);
        assertThat(detail.available()).isEqualTo(2);
        assertThat(detail.salesStatus().name()).isEqualTo("ON_SALE");
        assertThatThrownBy(() -> sales.changeStatus(sku.skuId(), SalesStatus.READY, "seller"))
                .isInstanceOf(
                        com.shoppinglive.commercecore.sales.exception
                                .IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("복수 SKU 내부 조회는 전체를 반환하고 단일 응답에서는 대표값 없이 충돌을 알린다")
    void multipleSkusAreNotCollapsed() {
        register(2);
        tx.executeWithoutResult(t -> skus.create(product.getId(), 2000, 3, "상품"));
        assertThat(queries.listSkus(product.getId(), "seller")).hasSize(2);
        assertThat(skus.findByProductIds(List.of(product.getId(), product.getId()))).hasSize(2);
        assertThatThrownBy(() -> sales.findSingleByProductIds(List.of(product.getId())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.list("seller", 0, 10))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.listSkus(product.getId(), "other"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 중지·삭제 후에도 기존 예약 소진·해제·복구는 같은 트랜잭션에서 가능하다")
    void completionDoesNotRevalidateNewSalePermission() {
        var sku = register(4);
        sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller");
        lifecycle.changeSaleState(product.getId(), true, 0L, "seller");
        var snapshot =
                tx.execute(
                        t -> {
                            trading.lockForNewOrder(List.of(product.getId()));
                            var locked = skus.lockForOrder(List.of(sku.skuId()));
                            skus.reserve(sku.skuId(), 2);
                            return locked.getFirst();
                        });
        lifecycle.delete(product.getId(), 1L, "seller");
        tx.executeWithoutResult(
                t -> {
                    skus.consume(sku.skuId(), 1);
                    skus.release(sku.skuId(), 1);
                    skus.restock(sku.skuId(), 1);
                });
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(snapshot.price()).isEqualTo(1000);
        assertThat(snapshot.available()).isEqualTo(4);
        assertThat(current.available()).isEqualTo(4);
        assertThat(current.reserved()).isZero();
    }

    @Test
    @DisplayName("Outbox 기록 실패는 SKU 가격과 이벤트 버전을 함께 롤백한다")
    void outboxFailureRollsBackSku() {
        var sku = register(2);
        jdbc.execute(
                "ALTER TABLE event_outbox ADD CONSTRAINT reject_sku_event CHECK(id <= "
                        + jdbc.queryForObject("SELECT max(id) FROM event_outbox", Long.class)
                        + ")");
        try {
            assertThatThrownBy(() -> sales.changePrice(sku.skuId(), 2000, "seller"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT reject_sku_event");
        }
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(current.price()).isEqualTo(1000);
        assertThat(current.version()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_version FROM sku WHERE id=?",
                                Long.class,
                                sku.skuId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("동시 기본 판매 등록은 SKU와 최초 Outbox를 한 번만 생성한다")
    void concurrentDefaultRegistrationCreatesOneSku() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call =
                    () -> {
                        start.await();
                        try {
                            register(2);
                            return true;
                        } catch (
                                com.shoppinglive.commercecore.sales.exception
                                                .SalesAlreadyRegisteredException
                                        conflict) {
                            return false;
                        }
                    };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(skus.findByProductId(product.getId())).hasSize(1);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("복수 SKU가 있는 상품도 페이지 전체 건수는 상품별로 계산하고 필터는 페이지보다 먼저 적용한다")
    void paginationCountsProductsBeforeReadingSingleSku() {
        register(2);
        tx.executeWithoutResult(t -> skus.create(product.getId(), 2000, 3, "상품"));
        var newest =
                products.saveAndFlush(
                        new Product("최신 상품", "설명", 1L, UUID.randomUUID().toString(), "seller"));
        sales.register(newest.getId(), 3000, 4, "seller");
        var page = queries.list("seller", 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().productId()).isEqualTo(newest.getId());
        var filtered =
                filters.sellerList(
                        "seller",
                        com.shoppinglive.commercecore.product.dto.SalesDisplayStatus.READY,
                        0,
                        1);
        assertThat(filtered.totalElements()).isEqualTo(2);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().getFirst().productId()).isEqualTo(newest.getId());
        assertThatThrownBy(() -> queries.list("seller", 1, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Autowired
    com.shoppinglive.commercecore.product.service.SellerProductCatalogQueryService filters;

    @Test
    @DisplayName("상품 전체 중지·재개는 SKU의 비공개 의도와 예약 수량을 덮어쓰지 않는다")
    void wholeProductSwitchPreservesSkuIntentAndQuantities() {
        var sku = register(2);
        sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller");
        tx.executeWithoutResult(t -> skus.reserve(sku.skuId(), 1));
        sales.changeStatus(sku.skuId(), SalesStatus.PRIVATE, "seller");
        lifecycle.changeSaleState(product.getId(), true, 0L, "seller");
        lifecycle.changeSaleState(product.getId(), false, 1L, "seller");
        lifecycle.changeSaleState(product.getId(), true, 2L, "seller");
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(current.status()).isEqualTo(SalesStatus.PRIVATE);
        assertThat(current.available()).isEqualTo(1);
        assertThat(current.reserved()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        t -> {
                                            trading.lockForNewOrder(List.of(product.getId()));
                                            skus.lockForOrder(List.of(sku.skuId()));
                                            skus.reserve(sku.skuId(), 1);
                                        }))
                .isInstanceOf(
                        com.shoppinglive.commercecore.sales.exception.InsufficientStockException
                                .class);
    }

    @Test
    @DisplayName("일반 상품 조회가 먼저 있어도 SKU 기본등록은 잠금 시 최신 Product 버전을 검증한다")
    void registrationRefreshesCachedProductBeforeLocking() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sku =
                    tx.execute(
                            t -> {
                                products.findById(product.getId()).orElseThrow();
                                try {
                                    executor.submit(
                                                    () ->
                                                            lifecycle.changeSaleState(
                                                                    product.getId(),
                                                                    true,
                                                                    0L,
                                                                    "seller"))
                                            .get(15, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new IllegalStateException(failure);
                                }
                                return register(2);
                            });
            assertThat(sku.status()).isEqualTo(SalesStatus.READY);
            assertThat(products.findById(product.getId()).orElseThrow().isSaleEnabled()).isTrue();
            assertThat(skus.findByProductId(product.getId())).hasSize(1);
        }
    }
}
