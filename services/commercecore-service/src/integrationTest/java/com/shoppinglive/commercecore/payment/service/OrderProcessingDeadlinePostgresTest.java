package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.service.OrderTimeSource;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class OrderProcessingDeadlinePostgresTest extends RefundTestSupport {
    @MockitoSpyBean OrderTimeSource time;

    @MockitoSpyBean(name = "paymentClient")
    PaymentClient client;

    @MockitoSpyBean LateApprovalRefundStore lateRefunds;
    @Autowired PaymentResultHandler handler;
    final Instant createdAt = Instant.parse("2026-11-16T00:00:00Z");

    @BeforeEach
    void freezeClockAndDisconnectPayment() {
        doReturn(createdAt).when(time).now();
        doThrow(new ResourceAccessException("전송 전 연결 실패")).when(client).start(any());
    }

    PaymentGroupService.GroupResponse create() {
        return paymentGroups
                .create(
                        MEMBER_A,
                        List.of(new PaymentGroupService.Selection(a.getId(), a.getVersion())),
                        "구매자",
                        "01012345678",
                        20000,
                        "processing-order")
                .group();
    }

    Instant deadline(long id) {
        return groups.findById(id).orElseThrow().getProcessingExpiresAt();
    }

    PaymentExecution resolved(PaymentExecution p, PaymentStatus status) {
        return new PaymentExecution(
                p.requestId(),
                p.paymentId(),
                p.orderId(),
                p.paymentGroupId(),
                p.referenceNumber(),
                p.amount(),
                p.scenario(),
                status,
                p.requestedAt(),
                p.requestedAt().plusSeconds(1),
                p.scheduledResolveAt());
    }

    @Test
    @DisplayName("HTTP 연결 실패의 재시도 시각은 JVM 시각과 섞지 않고 DB 시각 원본에 1초를 더한다")
    void deliveryRetryUsesDatabaseTimeSource() {
        Instant databaseTime =
                jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class)
                        .toInstant()
                        .minusSeconds(5);
        doReturn(databaseTime).when(time).now();
        var group = create();
        var request = paymentGroups.start(MEMBER_A, group.groupNumber(), "clock-approval");
        assertThat(request.requestedAt()).isEqualTo(databaseTime);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_action_at FROM payment_delivery_request WHERE payment_id=?",
                                        java.sql.Timestamp.class,
                                        request.paymentId())
                                .toInstant())
                .isEqualTo(databaseTime.plusSeconds(1));
        assertThat(deliveries.due(false))
                .extracting(PaymentDeliveryStore.Delivery::requestId)
                .contains(request.requestId());
    }

    @Test
    @DisplayName("생성600초와 실행600초는 별도이며 같은 승인 요청·조회는 기한을 연장하지 않는다")
    void preservesSeparateDeadlinesAndDurableRequestOnRetry() {
        var group = create();
        assertThat(group.expiresAt()).isEqualTo(createdAt.plusSeconds(600));
        doReturn(createdAt.plusSeconds(599)).when(time).now();
        var first = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        assertThat(deadline(first.paymentGroupId())).isEqualTo(createdAt.plusSeconds(1199));
        assertThat(groups.findById(first.paymentGroupId()).orElseThrow().getExpiresAt())
                .isEqualTo(group.expiresAt());
        doReturn(createdAt.plusSeconds(800)).when(time).now();
        var replay = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        paymentGroups.payment(MEMBER_A, group.groupNumber(), first.paymentId());
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(deadline(first.paymentGroupId())).isEqualTo(createdAt.plusSeconds(1199));
        assertThat(paymentGroups.expire(first.paymentGroupId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_delivery_request", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("처리 기한 직전에는 유지하고 정확 경계에서 만료·선점해제를 한 번만 한다")
    void expiresAtExactProcessingBoundary() {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        Instant boundary = deadline(p.paymentGroupId());
        doReturn(boundary.minusNanos(1000)).when(time).now();
        assertThat(paymentGroups.expire(p.paymentGroupId())).isFalse();
        doReturn(boundary).when(time).now();
        assertThat(groups.expired(boundary, org.springframework.data.domain.PageRequest.of(0, 10)))
                .contains(p.paymentGroupId());
        assertThat(paymentGroups.expire(p.paymentGroupId())).isTrue();
        assertThat(paymentGroups.expire(p.paymentGroupId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_intent", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE topic='commerce.refund.requests.v1'",
                                Long.class))
                .isZero();
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getReserved()).isZero();
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getAvailable())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("만료된 승인과 HTTP·이벤트 중복은 주문을 복구하지 않고 환불 필요 사실을 한 번 저장한다")
    void recordsLateApprovalOnceWithoutRevivingOrder() {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        doReturn(deadline(p.paymentGroupId())).when(time).now();
        var success = resolved(p, PaymentStatus.SUCCESS);
        assertThat(handler.payment(success)).isTrue();
        assertThat(handler.payment(success)).isFalse();
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refund_request_id FROM late_approval_refund", String.class))
                .isEqualTo("late-" + p.paymentId());
        assertThat(jdbc.queryForObject("SELECT status FROM late_approval_refund", String.class))
                .isEqualTo("REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_approval_refund", Long.class))
                .isEqualTo(1);
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getAvailable())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("기한 내 승인만 PAID로 확정하고 재고를 소진한다")
    void paysOnlyBeforeDeadline() {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        doReturn(deadline(p.paymentGroupId()).minusNanos(1000)).when(time).now();
        assertThat(handler.payment(resolved(p, PaymentStatus.SUCCESS))).isTrue();
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.PAID);
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getAvailable())
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_approval_refund", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("만료 후 확정 실패는 재고를 다시 해제하지 않는다")
    void failureAfterExpiryDoesNotReleaseTwice() {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        doReturn(deadline(p.paymentGroupId())).when(time).now();
        assertThat(paymentGroups.expire(p.paymentGroupId())).isTrue();
        assertThat(handler.payment(resolved(p, PaymentStatus.FAILED))).isTrue();
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getAvailable())
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_approval_refund", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("늦은 승인 저장 실패는 주문 만료·선점해제·결과 기록을 함께 롤백한다")
    void rollsBackExpiryAndOutcomeIfLateApprovalCannotBeRecorded() {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        doReturn(deadline(p.paymentGroupId())).when(time).now();
        LateApprovalRefundStore target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(lateRefunds);
        doThrow(new IllegalStateException("환불 필요 기록 실패")).when(target).require(any(), any());
        assertThatThrownBy(() -> handler.payment(resolved(p, PaymentStatus.SUCCESS)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(groups.findById(p.paymentGroupId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        assertThat(deliveries.byPayment(p.paymentId()).status()).isEqualTo("PROCESSING");
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getReserved())
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_approval_refund", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("실행 접수 중 생성 기한을 넘기면 상태와 HTTP 전달 요청을 함께 롤백한다")
    void rollsBackDeliveryIfPendingDeadlinePassesDuringAcceptance() {
        var group = create();
        doReturn(createdAt.plusSeconds(599), createdAt.plusSeconds(600)).when(time).now();
        assertThatThrownBy(() -> paymentGroups.start(MEMBER_A, group.groupNumber(), "approval"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_delivery_request", Long.class))
                .isZero();
        assertThat(
                        groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(client, never()).start(any());
    }

    @Test
    @DisplayName("기한 경계의 승인과 만료가 경합해도 만료 주문·환불 필요 한 건·선점해제 한 번으로 끝난다")
    void serializesConcurrentExpiryAndApproval() throws Exception {
        var group = create();
        var p = paymentGroups.start(MEMBER_A, group.groupNumber(), "approval");
        doReturn(deadline(p.paymentGroupId())).when(time).now();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var expiry =
                    pool.submit(
                            () -> {
                                gate.await();
                                return paymentGroups.expire(p.paymentGroupId());
                            });
            var approval =
                    pool.submit(
                            () -> {
                                gate.await();
                                return handler.payment(resolved(p, PaymentStatus.SUCCESS));
                            });
            gate.countDown();
            expiry.get(15, TimeUnit.SECONDS);
            assertThat(approval.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(groups.findById(p.paymentGroupId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getAvailable())
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM late_approval_refund", Long.class))
                .isEqualTo(1);
    }
}
