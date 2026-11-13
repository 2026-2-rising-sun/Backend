package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

import com.shoppinglive.commercecore.product.config.ProductConfiguration;
import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.product.entity.*;
import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.commercecore.product.repository.*;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.*;
import com.shoppinglive.commercecore.storage.*;
import com.shoppinglive.common.outbox.*;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootTest(
        classes = ProductPersistenceTest.Configuration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:product_persistence;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "shoppinglive.outbox.relay.enabled=false",
            "commercecore.image.public-base-url=/api/commercecore"
        })
class ProductPersistenceTest {
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {Product.class, OutboxMessage.class, Sales.class})
    @EnableJpaRepositories(
            basePackageClasses = {
                ProductRepository.class,
                OutboxMessageRepository.class,
                SalesJpaRepository.class
            })
    @Import({
        JpaAuditingConfig.class,
        OutboxConfiguration.class,
        ProductConfiguration.class,
        LocalImageUrlResolver.class,
        ProductRegistrationService.class,
        ProductUpdateService.class,
        ProductLifecycleService.class,
        ProductTradingService.class,
        ProductInfoEventPublisher.class,
        ProductQueryService.class,
        SellerProductQueryService.class,
        SellerProductCatalogQueryService.class
    })
    static class Configuration {}

    @Autowired ProductRegistrationService registration;
    @Autowired ProductUpdateService updates;
    @Autowired ProductLifecycleService lifecycle;
    @Autowired ProductTradingService trading;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired ProductQueryService lookup;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired OutboxMessageRepository messages;
    @Autowired SellerProductQueryService sellerQueries;
    @Autowired SellerProductCatalogQueryService sellerFilters;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private long imageId;

    @BeforeEach
    void prepare() {
        imageId =
                images.saveAndFlush(
                                new ProductImage(
                                        UUID.randomUUID() + ".png",
                                        ImageFormat.PNG,
                                        100,
                                        10,
                                        10,
                                        "fixture.png"))
                        .getId();
    }

    @AfterEach
    void clean() {
        stocks.deleteAll();
        sales.deleteAll();
        products.deleteAll();
        images.deleteAll();
        messages.deleteAll();
    }

    private RegisterProductCommand command() {
        return new RegisterProductCommand("상품", "설명", imageId, ProductCategory.FASHION);
    }

    @Test
    @DisplayName("등록 재전송은 상품과 최초 version 0 Outbox를 각각 한 번만 생성한다")
    void replayKeepsOneOriginalAndOutbox() {
        Product first = registration.register(command(), "replay", "seller");
        Product replay = registration.register(command(), "replay", "seller");
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(first.getVersion()).isZero();
        assertThat(products.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("등록 후 Outbox 기록이 실패하면 상품과 이벤트가 함께 롤백된다")
    void outboxFailureRollsBackProduct() {
        jdbc.execute(
                "ALTER TABLE event_outbox ADD CONSTRAINT reject_product_event CHECK (topic <> 'commerce.product-info.v1')");
        try {
            assertThatThrownBy(() -> registration.register(command(), "rollback", "seller"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT reject_product_event");
        }
        assertThat(products.count()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    @DisplayName("수정은 버전을 올려 snapshot을 기록하고 같은 값·오래된 버전은 추가 이벤트나 덮어쓰기를 만들지 않는다")
    void updateRecordsOnlyChangedVersions() {
        Product p = registration.register(command(), "update", "seller");
        Product updated =
                updates.update(
                        p.getId(),
                        new UpdateProductCommand("새 이름", null, null, null, 0L),
                        "seller");
        assertThat(updated.getVersion()).isEqualTo(1);
        updates.update(p.getId(), new UpdateProductCommand("새 이름", null, null, null, 1L), "seller");
        assertThat(messages.count()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                updates.update(
                                        p.getId(),
                                        new UpdateProductCommand("덮어쓰기", null, null, null, 0L),
                                        "seller"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(products.findById(p.getId()).orElseThrow().getName()).isEqualTo("새 이름");
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("동일 멱등키의 동시 등록은 원본과 snapshot을 한 번만 저장한다")
    void concurrentRegistrationKeepsOneProductAndSnapshot() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> request =
                    () -> {
                        start.await();
                        return registration.register(command(), "concurrent", "seller").getId();
                    };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(products.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 버전의 동시 수정은 하나만 반영하고 snapshot도 한 번만 추가한다")
    void concurrentUpdatesNeverOverwriteTheWinner() throws Exception {
        Product original = registration.register(command(), "concurrent-update", "seller");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String name : List.of("수정 A", "수정 B")) {
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        updates.update(
                                                original.getId(),
                                                new UpdateProductCommand(
                                                        name, null, null, null, 0L),
                                                "seller");
                                        return true;
                                    } catch (
                                            com.shoppinglive.common.core.BusinessException
                                                    conflict) {
                                        assertThat(conflict.errorCode())
                                                .isEqualTo(
                                                        com.shoppinglive.common.core.ErrorCode
                                                                .CONFLICT);
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            assertThat(
                            List.of(
                                    results.get(0).get(20, TimeUnit.SECONDS),
                                    results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(products.findById(original.getId()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("로컬 상품·판매자 조회는 사본 없이 즉시 최신 원본과 판매·재고를 반환한다")
    void localReadsUseOriginalProductAndSales() {
        Product p = registration.register(command(), "local", "seller");
        var sale = sales.saveAndFlush(new Sales(p.getId(), 39000L, SalesStatus.ON_SALE));
        stocks.saveAndFlush(new SalesStock(sale.getId(), 4, 0));
        assertThat(lookup.findProduct(p.getId()).orElseThrow())
                .isEqualTo(
                        new ProductSnapshot(
                                p.getId(),
                                "상품",
                                "/api/commercecore/v1/product-images/" + imageId,
                                "seller",
                                false));
        assertThat(lookup.findProducts(List.of(p.getId(), p.getId(), Long.MAX_VALUE))).hasSize(1);
        assertThat(lookup.exists(Long.MAX_VALUE)).isFalse();
        var detail = sellerQueries.get(p.getId(), "seller");
        assertThat(detail.price()).isEqualTo(39000);
        assertThat(detail.available()).isEqualTo(4);
        assertThat(detail.salesStatus()).isEqualTo(SalesDisplayStatus.ON_SALE);
        assertThat(sellerQueries.list("seller", 0, 20).items()).hasSize(1);
        assertThat(sellerFilters.sellerList("seller", SalesDisplayStatus.ON_SALE, 0, 20).items())
                .hasSize(1);
        assertThat(sellerQueries.list("other", 0, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("전체 중지·재개와 같은 상태 재요청은 개별 판매 상태·수량을 바꾸지 않는다")
    void wholeProductControlPreservesIndividualSaleAndStock() {
        Product p = registration.register(command(), "control", "seller");
        assertThat(p.isSaleEnabled()).isFalse();
        var sale = sales.saveAndFlush(new Sales(p.getId(), 39000L, SalesStatus.PRIVATE));
        stocks.saveAndFlush(new SalesStock(sale.getId(), 4, 2));
        Product enabled = lifecycle.changeSaleState(p.getId(), true, 0L, "seller");
        assertThat(enabled.getVersion()).isEqualTo(1);
        lifecycle.changeSaleState(p.getId(), true, 1L, "seller");
        assertThat(messages.count()).isEqualTo(2);
        lifecycle.changeSaleState(p.getId(), false, 1L, "seller");
        lifecycle.changeSaleState(p.getId(), true, 2L, "seller");
        assertThat(sales.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.PRIVATE);
        var stock = stocks.findById(sale.getId()).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(4);
        assertThat(stock.getReserved()).isEqualTo(2);
        assertThat(sellerQueries.get(p.getId(), "seller").saleEnabled()).isTrue();
    }

    @Test
    @DisplayName("삭제는 본인과 버전을 검사하고 재전송 시 이벤트를 중복하지 않으며 원본·이미지를 보존한다")
    void deletionChecksOwnershipAndVersionAndPreservesHistory() {
        Product p = registration.register(command(), "delete", "seller");
        assertThatThrownBy(() -> lifecycle.delete(p.getId(), 0L, "other"))
                .isInstanceOfSatisfying(
                        com.shoppinglive.common.core.BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(
                                                com.shoppinglive.common.core.ErrorCode.FORBIDDEN));
        lifecycle.changeSaleState(p.getId(), true, 0L, "seller");
        assertThatThrownBy(() -> lifecycle.delete(p.getId(), 0L, "seller"))
                .isInstanceOfSatisfying(
                        com.shoppinglive.common.core.BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(
                                                com.shoppinglive.common.core.ErrorCode.CONFLICT));
        lifecycle.delete(p.getId(), 1L, "seller");
        lifecycle.delete(p.getId(), 1L, "seller");
        Product deleted = products.findById(p.getId()).orElseThrow();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.isSaleEnabled()).isFalse();
        assertThat(deleted.getVersion()).isEqualTo(2);
        assertThat(messages.count()).isEqualTo(3);
        assertThat(images.existsById(imageId)).isTrue();
        assertThat(lookup.findProduct(p.getId())).isEmpty();
        assertThat(lookup.findProducts(List.of(p.getId()))).isEmpty();
        assertThat(sellerQueries.list("seller", 0, 20).totalElements()).isZero();
        assertThatThrownBy(() -> lifecycle.changeSaleState(p.getId(), true, 2L, "seller"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThatThrownBy(
                        () ->
                                updates.update(
                                        p.getId(),
                                        new UpdateProductCommand("복구", null, null, null, 2L),
                                        "seller"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
    }

    @Test
    @DisplayName("Outbox 실패는 전체 판매 제어와 삭제도 함께 롤백한다")
    void lifecycleOutboxFailureRollsBackTheWholeChange() {
        Product p = registration.register(command(), "control-rollback", "seller");
        messages.deleteAll();
        jdbc.execute(
                "ALTER TABLE event_outbox ADD CONSTRAINT reject_control_event CHECK (topic <> 'commerce.product-info.v1')");
        try {
            assertThatThrownBy(() -> lifecycle.changeSaleState(p.getId(), true, 0L, "seller"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> lifecycle.delete(p.getId(), 0L, "seller"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT reject_control_event");
        }
        Product unchanged = products.findById(p.getId()).orElseThrow();
        assertThat(unchanged.isSaleEnabled()).isFalse();
        assertThat(unchanged.getDeletedAt()).isNull();
        assertThat(unchanged.getVersion()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    @DisplayName("판매 준비 중 SKU 등록은 허용하고 신규 주문은 모든 상품 허용과 호출자 트랜잭션을 요구한다")
    void tradingPortRequiresCallerTransactionAndCompleteEligibility() {
        Product p = registration.register(command(), "trade", "seller");
        assertThatThrownBy(() -> trading.lockForNewOrder(List.of(p.getId())))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(
                        transactions
                                .execute(status -> trading.lockForSkuCreation(p.getId(), "seller"))
                                .productId())
                .isEqualTo(p.getId());
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> trading.lockForSkuCreation(p.getId(), "other")))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> trading.lockForNewOrder(List.of(p.getId()))))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        lifecycle.changeSaleState(p.getId(), true, 0L, "seller");
        var snapshot =
                transactions
                        .execute(status -> trading.lockForNewOrder(List.of(p.getId(), p.getId())))
                        .getFirst();
        updates.update(
                p.getId(), new UpdateProductCommand("바뀐 상품", null, null, null, 1L), "seller");
        assertThat(snapshot.name()).isEqualTo("상품");
        assertThat(snapshot.version()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status ->
                                                trading.lockForNewOrder(
                                                        List.of(p.getId(), Long.MAX_VALUE))))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        lifecycle.delete(p.getId(), 2L, "seller");
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> trading.lockForSkuCreation(p.getId(), "seller")))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
    }

    @Test
    @DisplayName("이미지 FK 없이도 등록·대표 이미지 교체는 없는 이미지를 거절한다")
    void applicationValidationRejectsMissingImageReferences() {
        assertThatThrownBy(
                        () ->
                                registration.register(
                                        new RegisterProductCommand(
                                                "상품", "설명", Long.MAX_VALUE, null),
                                        "missing-image",
                                        "seller"))
                .isInstanceOfSatisfying(
                        com.shoppinglive.common.core.BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(
                                                com.shoppinglive.common.core.ErrorCode
                                                        .INVALID_REQUEST));
        Product p = registration.register(command(), "image-validation", "seller");
        assertThatThrownBy(
                        () ->
                                updates.update(
                                        p.getId(),
                                        new UpdateProductCommand(
                                                null, null, Long.MAX_VALUE, null, 0L),
                                        "seller"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(products.findById(p.getId()).orElseThrow().getMainImageId()).isEqualTo(imageId);
    }

    @Test
    @DisplayName("거래 전 일반 조회가 영속성 컨텍스트에 있어도 먼저 완료된 중지·삭제의 최신 상태로 거절한다")
    void priorDisplayReadCannotBypassCommittedControl() {
        for (boolean delete : new boolean[] {false, true}) {
            Product p = registration.register(command(), UUID.randomUUID().toString(), "seller");
            lifecycle.changeSaleState(p.getId(), true, 0L, "seller");
            try (var executor = Executors.newSingleThreadExecutor()) {
                transactions.executeWithoutResult(
                        status -> {
                            assertThat(products.findById(p.getId()).orElseThrow().isSaleEnabled())
                                    .isTrue();
                            CompletableFuture.runAsync(
                                            () -> {
                                                if (delete)
                                                    lifecycle.delete(p.getId(), 1L, "seller");
                                                else
                                                    lifecycle.changeSaleState(
                                                            p.getId(), false, 1L, "seller");
                                            },
                                            executor)
                                    .orTimeout(10, TimeUnit.SECONDS)
                                    .join();
                            assertThatThrownBy(() -> trading.lockForNewOrder(List.of(p.getId())))
                                    .isInstanceOf(
                                            com.shoppinglive.common.core.BusinessException.class);
                            status.setRollbackOnly();
                        });
            }
        }
    }
}
