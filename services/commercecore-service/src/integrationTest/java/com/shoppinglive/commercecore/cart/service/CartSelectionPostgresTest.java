package com.shoppinglive.commercecore.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.dto.SelectedCartItem;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartSelectionPostgresTest extends CartSelectionServiceTest {
    private static final String MEMBER = "33333333-3333-4333-8333-333333333333";
    private static final String SCHEMA =
            "cart_selection_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    private static final String USER =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_PASSWORD", "postgres");
    @Autowired CartSelectionPort port;
    @Autowired CartItemRepository cartItems;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add(
                "spring.datasource.url",
                () ->
                        URL
                                + (URL.contains("?") ? "&" : "?")
                                + "currentSchema="
                                + SCHEMA
                                + "&ApplicationName="
                                + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanupOwnedSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("반대 순서의 동시 선택도 PostgreSQL 행 잠금을 기다린 뒤 같은 ID 순서로 완료한다")
    void serializesConcurrentSelectionsWithActualPostgresLocks() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<CartSelection> selected =
                transaction.execute(
                        status -> {
                            CartItem first =
                                    cartItems.saveAndFlush(new CartItem(MEMBER, 501L, 1101L, 2));
                            CartItem second =
                                    cartItems.saveAndFlush(new CartItem(MEMBER, 501L, 1102L, 5));
                            return List.of(
                                    new CartSelection(first.getId(), first.getVersion()),
                                    new CartSelection(second.getId(), second.getVersion()));
                        });
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                List<SelectedCartItem> result =
                                                        port.lockOwned(MEMBER, selected);
                                                locked.countDown();
                                                await(release);
                                                return result;
                                            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var second =
                    executor.submit(
                            () ->
                                    transaction.execute(
                                            status -> port.lockOwned(MEMBER, selected.reversed())));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                                                            Integer.class,
                                                            SCHEMA))
                                            .isGreaterThan(0));
            assertThat(second.isDone()).isFalse();
            release.countDown();
            List<Long> ids = selected.stream().map(CartSelection::itemId).toList();
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .extracting(SelectedCartItem::itemId)
                    .containsExactlyElementsOf(ids);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .extracting(SelectedCartItem::itemId)
                    .containsExactlyElementsOf(ids);
        } finally {
            release.countDown();
            executor.shutdownNow();
            transaction.executeWithoutResult(
                    status ->
                            cartItems.deleteAllById(
                                    selected.stream().map(CartSelection::itemId).toList()));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("성공 정리 뒤 거래가 실패하면 PostgreSQL에서 삭제도 함께 롤백한다")
    void rollsBackCleanupWithCallersTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CartSelection selected =
                transaction.execute(
                        status -> {
                            CartItem item =
                                    cartItems.saveAndFlush(new CartItem(MEMBER, 501L, 1201L, 2));
                            return new CartSelection(item.getId(), item.getVersion());
                        });
        try {
            assertThatThrownBy(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                port.lockForCompletion(
                                                        MEMBER, List.of(selected.itemId()));
                                                assertThat(
                                                                port.deletePurchased(
                                                                        MEMBER, List.of(selected)))
                                                        .isEqualTo(1);
                                                throw new IllegalStateException("주문 결과 반영 실패");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("주문 결과 반영 실패");
            assertThat(cartItems.existsById(selected.itemId())).isTrue();
        } finally {
            transaction.executeWithoutResult(status -> cartItems.deleteById(selected.itemId()));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("수량 변경 거래를 기다린 성공 정리는 이전 버전의 항목을 삭제하지 않는다")
    void preservesQuantityChangeCommittedWhileCleanupWaits() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CartSelection selected =
                transaction.execute(
                        status -> {
                            CartItem item =
                                    cartItems.saveAndFlush(new CartItem(MEMBER, 501L, 1301L, 2));
                            return new CartSelection(item.getId(), item.getVersion());
                        });
        var changed = new CountDownLatch(1);
        var commitChange = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var update =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                CartItem item =
                                                        cartItems
                                                                .lockOwned(
                                                                        selected.itemId(), MEMBER)
                                                                .orElseThrow();
                                                item.changeQuantity(5);
                                                cartItems.saveAndFlush(item);
                                                changed.countDown();
                                                await(commitChange);
                                            }));
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            var cleanup =
                    executor.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                port.lockForCompletion(
                                                        MEMBER, List.of(selected.itemId()));
                                                return port.deletePurchased(
                                                        MEMBER, List.of(selected));
                                            }));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                                                            Integer.class,
                                                            SCHEMA))
                                            .isGreaterThan(0));
            assertThat(cleanup.isDone()).isFalse();
            commitChange.countDown();
            update.get(5, TimeUnit.SECONDS);
            assertThat(cleanup.get(5, TimeUnit.SECONDS)).isZero();
            CartItem remaining = cartItems.findById(selected.itemId()).orElseThrow();
            assertThat(remaining.getQuantity()).isEqualTo(5);
            assertThat(remaining.getVersion()).isEqualTo(selected.version() + 1);
        } finally {
            commitChange.countDown();
            executor.shutdownNow();
            transaction.executeWithoutResult(status -> cartItems.deleteById(selected.itemId()));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
