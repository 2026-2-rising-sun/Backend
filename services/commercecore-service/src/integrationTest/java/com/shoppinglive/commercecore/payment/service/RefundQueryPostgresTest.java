package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commercecore.payment.dto.RefundView;
import com.shoppinglive.commercecore.payment.dto.SellerRefundView;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DelegatingDataSource;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class RefundQueryPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService intake;
    @Autowired RefundQueryService queries;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private record History(String groupNumber, long groupId, List<Long> requestIds) {}

    private History history(int count, boolean seller) {
        var group = createAndPay();
        long groupId =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        var groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(groupId);
        List<Long> ids = new ArrayList<>();
        int failedCount = count == 1 ? 0 : count - (seller ? 1 : 2);
        Instant now = Instant.now().minusSeconds(count + 10L);
        for (int i = 0; i < failedCount; i++) {
            ids.add(
                    insertRequest(
                            groupId, "history-" + i, "FAILED", 25000L, now.plusSeconds(i / 2)));
            addTarget(ids.getLast(), groupOrders.get(0).getId(), 81001L, SELLER, 20000L);
            addTarget(ids.getLast(), groupOrders.get(1).getId(), 81002L, SELLER_B, 5000L);
        }
        ids.add(insertRequest(groupId, "success-a", "SUCCESS", 20000L, now.plusSeconds(count)));
        addTarget(ids.getLast(), groupOrders.get(0).getId(), 81001L, SELLER, 20000L);
        if (count > 1) {
            ids.add(
                    insertRequest(
                            groupId, "success-b", "SUCCESS", 5000L, now.plusSeconds(count + 1)));
            addTarget(ids.getLast(), groupOrders.get(1).getId(), 81002L, SELLER_B, 5000L);
        }
        return new History(group.groupNumber(), groupId, ids);
    }

    private long insertRequest(long groupId, String key, String status, long amount, Instant at) {
        return jdbc.queryForObject(
                """
            INSERT INTO refund_request(payment_group_id,member_id,idempotency_key,
                request_fingerprint,refund_amount,status,requested_at,resolved_at,created_at,updated_at)
            VALUES (?,?,?,? ,?,?,?, ?,?,?) RETURNING id
            """,
                Long.class,
                groupId,
                MEMBER_A,
                key,
                "query-fixture",
                amount,
                status,
                Timestamp.from(at),
                Timestamp.from(at.plusSeconds(1)),
                Timestamp.from(at),
                Timestamp.from(at));
    }

    private void addTarget(
            long requestId, long orderId, long productId, String seller, long amount) {
        jdbc.update(
                """
            INSERT INTO refund_target_order(refund_request_id,order_id,product_id_snapshot,
                seller_id_snapshot,refund_amount,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
            """,
                requestId,
                orderId,
                productId,
                seller,
                amount);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "100,false", "1,true", "100,true"})
    @DisplayName("구매자와 판매자의 1건·100건 목록은 기존 상세 응답과 같고 SQL 수를 제한한다")
    void preservesListResponsesAndBoundsSql(int count, boolean seller) {
        History history = history(count, seller);
        if (seller) {
            List<Long> ids =
                    jdbc.query(
                            """
                SELECT DISTINCT r.id FROM refund_request r JOIN refund_target_order t ON t.refund_request_id=r.id
                WHERE t.seller_id_snapshot=? ORDER BY r.id DESC LIMIT 100
                """,
                            (rs, row) -> rs.getLong(1),
                            SELLER);
            List<SellerRefundView> expected =
                    ids.stream().map(id -> queries.getSeller(SELLER, id)).toList();
            var actual = measured(count, true, () -> queries.listSeller(SELLER, 0, 100));
            assertThat(actual).hasSize(count).isEqualTo(expected);
            assertThat(actual)
                    .allSatisfy(
                            view -> {
                                assertThat(view.refundAmount()).isEqualTo(20000L);
                                assertThat(view.cumulativeRefundAmount()).isEqualTo(20000L);
                                assertThat(view.targets()).hasSize(1);
                                assertThat(view.targets().getFirst().productId()).isEqualTo(81001L);
                            });
        } else {
            List<Long> ids =
                    jdbc.query(
                            """
                SELECT id FROM refund_request WHERE payment_group_id=? ORDER BY requested_at DESC,id DESC
                """,
                            (rs, row) -> rs.getLong(1),
                            history.groupId());
            List<RefundView> expected =
                    ids.stream()
                            .map(id -> queries.get(MEMBER_A, history.groupNumber(), id))
                            .toList();
            var actual =
                    measured(count, false, () -> queries.list(MEMBER_A, history.groupNumber()));
            assertThat(actual).hasSize(count).isEqualTo(expected);
            assertThat(actual)
                    .allSatisfy(
                            view ->
                                    assertThat(view.cumulativeRefundAmount())
                                            .isEqualTo(count == 1 ? 20000L : 25000L));
            assertThat(actual.getFirst().targets()).hasSize(1);
            assertThat(actual.getFirst().targets().getFirst().cartItemId())
                    .isEqualTo(count == 1 ? a.getId() : b.getId());
            if (count > 1) {
                assertThat(actual.get(2).targets())
                        .extracting(target -> target.orderId())
                        .isSorted();
                assertThat(actual.get(2).targets())
                        .extracting(target -> target.quantity())
                        .containsExactly(2, 1);
            }
        }
    }

    private <T> T measured(int count, boolean seller, Supplier<T> action) {
        DataSource original = jdbc.getDataSource();
        CountingDataSource counting = new CountingDataSource(original);
        var statistics =
                entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        jdbc.setDataSource(counting);
        T result;
        long ownershipSql;
        try {
            result = action.get();
            ownershipSql = statistics.getPrepareStatementCount();
        } finally {
            jdbc.setDataSource(original);
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
        int expected = 1 + 3 * count;
        System.out.printf(
                "환불 목록 SQL: seller=%s, rows=%d, queries=%d, expected=%d%n",
                seller, count, counting.sql.size(), expected);
        System.out.printf(
                "환불 목록 소유권 SQL: seller=%s, rows=%d, queries=%d%n", seller, count, ownershipSql);
        assertThat(ownershipSql).isEqualTo(seller ? 0L : 1L);
        assertThat(counting.sql).hasSize(expected);
        return result;
    }

    @Test
    @DisplayName("판매자 페이지 밖의 성공 환불도 누적하고 타 판매자의 금액과 대상은 숨긴다")
    void preservesSellerPaginationCumulativeScopeAndIsolation() {
        History history = history(100, true);
        var all = queries.listSeller(SELLER, 0, 100);
        var first = queries.listSeller(SELLER, 0, 20);
        var second = queries.listSeller(SELLER, 1, 20);
        assertThat(first).isEqualTo(all.subList(0, 20));
        assertThat(second).isEqualTo(all.subList(20, 40));
        assertThat(second)
                .allSatisfy(
                        view -> {
                            assertThat(view.status()).isEqualTo(RefundStatus.FAILED);
                            assertThat(view.cumulativeRefundAmount()).isEqualTo(20000L);
                            assertThat(view.targets()).hasSize(1);
                        });
        assertThat(queries.listSeller(SELLER_B, 0, 100))
                .allSatisfy(
                        view -> {
                            assertThat(view.refundAmount()).isEqualTo(5000L);
                            assertThat(view.cumulativeRefundAmount()).isEqualTo(5000L);
                            assertThat(view.targets())
                                    .extracting(target -> target.productId())
                                    .containsExactly(81002L);
                        });
        assertThat(queries.listSeller(SELLER, 1, 100)).isEmpty();
        assertNotFound(() -> queries.getSeller(SELLER, history.requestIds().getLast()));
        assertNotFound(() -> queries.getSeller(MEMBER_B, history.requestIds().getFirst()));
        assertNotFound(
                () ->
                        queries.get(
                                MEMBER_B, history.groupNumber(), history.requestIds().getFirst()));
        assertNotFound(() -> queries.list(MEMBER_B, history.groupNumber()));
        assertNotFound(() -> queries.get(MEMBER_A, history.groupNumber(), Long.MAX_VALUE));
        assertThatThrownBy(() -> queries.listSeller(SELLER, -1, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.listSeller(SELLER, 0, 101))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("환불이 없는 목록은 비어 있고 처리 중 복구 정보와 부분 환불 응답을 유지한다")
    void preservesEmptyListAndRecoveryResponse() {
        var group = createAndPay();
        assertThat(queries.list(MEMBER_A, group.groupNumber())).isEmpty();
        assertThat(queries.listSeller(SELLER, 0, 100)).isEmpty();
        var refund =
                intake.request(MEMBER_A, group.groupNumber(), "partial", List.of(a.getId()))
                        .refund();
        Instant next = Instant.parse("2026-11-09T00:00:00Z");
        jdbc.update(
                "UPDATE refund_request SET status='UNKNOWN',retry_count=3,next_action_at=? WHERE id=?",
                Timestamp.from(next),
                refund.id());
        var view = queries.list(MEMBER_A, group.groupNumber()).getFirst();
        assertThat(view.refundAmount()).isEqualTo(20000L);
        assertThat(view.cumulativeRefundAmount()).isZero();
        assertThat(view.recovery().retryCount()).isEqualTo(3);
        assertThat(view.recovery().retryExhausted()).isTrue();
        assertThat(view.recovery().nextActionAt()).isEqualTo(next);
        assertThat(view.targets()).hasSize(1);
        assertThat(view.targets().getFirst().quantity()).isEqualTo(2);
        assertThat(queries.listSeller(SELLER_B, 0, 100)).isEmpty();
        assertThat(queries.refundableTargets(MEMBER_A, group.groupNumber()))
                .anySatisfy(
                        target -> {
                            assertThat(target.cartItemId()).isEqualTo(a.getId());
                            assertThat(target.unavailableReason()).isEqualTo("ALREADY_REQUESTED");
                        })
                .anySatisfy(
                        target -> {
                            assertThat(target.cartItemId()).isEqualTo(b.getId());
                            assertThat(target.refundable()).isTrue();
                        });
    }

    @Test
    @DisplayName("여러 결제 묶음을 한 페이지로 조회해도 묶음별 성공 누적액을 구분한다")
    void keepsCumulativeAmountsSeparateAcrossGroups() {
        History first = history(1, false);
        var group =
                groups.saveAndFlush(
                        new com.shoppinglive.commercecore.order.entity.PaymentGroup(
                                "PG-query-second",
                                MEMBER_A,
                                "query-second",
                                "query-second",
                                7000L,
                                Instant.now().plusSeconds(3600)));
        var order =
                new com.shoppinglive.commercecore.order.entity.Order(
                        "OD-query-second",
                        sales.findAll().getFirst().getId(),
                        1,
                        7000L,
                        "구매자",
                        "01012345678",
                        MEMBER_A,
                        "두 번째 상품",
                        "query-second-order",
                        group.getExpiresAt());
        order.attachGroup(group, null);
        order = orders.saveAndFlush(order);
        long id = insertRequest(group.getId(), "second-success", "SUCCESS", 7000L, Instant.now());
        addTarget(id, order.getId(), 81001L, SELLER, 7000L);
        var page = queries.listSeller(SELLER, 0, 100);
        assertThat(page).hasSize(2);
        assertThat(page.getFirst().id()).isEqualTo(id);
        assertThat(page.getFirst().cumulativeRefundAmount()).isEqualTo(7000L);
        assertThat(page.get(1).id()).isEqualTo(first.requestIds().getFirst());
        assertThat(page.get(1).cumulativeRefundAmount()).isEqualTo(20000L);
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // SQL 구성이나 메서드 호출 수가 아니라 실제 PostgreSQL PreparedStatement 실행을 센다.
    // JPA의 결제 묶음 소유권 조회는 이 목록 데이터 JDBC 카운터와 별도다.
    private static class CountingDataSource extends DelegatingDataSource {
        private final List<String> sql = new ArrayList<>();

        CountingDataSource(DataSource original) {
            super(original);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                try {
                                    Object result = method.invoke(connection, args);
                                    if (method.getName().equals("prepareStatement")
                                            && args[0] instanceof String query) {
                                        PreparedStatement statement = (PreparedStatement) result;
                                        return Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[] {PreparedStatement.class},
                                                (prepared, operation, parameters) -> {
                                                    if (operation.getName().startsWith("execute"))
                                                        sql.add(query);
                                                    try {
                                                        return operation.invoke(
                                                                statement, parameters);
                                                    } catch (InvocationTargetException failure) {
                                                        throw failure.getCause();
                                                    }
                                                });
                                    }
                                    return result;
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }
    }
}
