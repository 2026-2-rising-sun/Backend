package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
        properties = {
            "commercecore.refunds.execution-enabled=false",
            "commercecore.order.closure-recovery.initial-delay=PT1H"
        })
@AutoConfigureMockMvc
class OrderSheetCompensationPostgresTest extends OrderSheetPostgresSupport {
    @MockitoSpyBean SkuService skuSpy;

    @Test
    @DisplayName("실제 Payment HTTP 종료 경로는 무토큰·오류토큰·사용자 Bearer를 거절한다")
    void actualHttpClosureRejectsNonServiceCallers() throws Exception {
        int port =
                ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                                paymentServer.context)
                        .getWebServer()
                        .getPort();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            for (int mode = 0; mode < 3; mode++) {
                var request =
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/v1/internal/payments/1/order-closure"))
                                .header("Content-Type", "application/json")
                                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{}"));
                if (mode == 1) request.header("X-Service-Token", "wrong-token");
                if (mode == 2) request.header("Authorization", bearer(MEMBER_A));
                assertThat(
                                http.send(
                                                request.build(),
                                                java.net.http.HttpResponse.BodyHandlers.ofString())
                                        .statusCode())
                        .isEqualTo(401);
            }
        }
    }

    @Test
    @DisplayName("만료 후 늦은 성공은 주문을 되살리지 않고 일반 환불 off에서도 보상만 전달한다")
    void compensatesLateSuccessWithoutRepeatingOrderEffects() {
        var order = createCart(501, 1);
        long cart = order.items().getFirst().sourceCartItemId();
        var payment = start(order, true);
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        makeExpired(order);
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        dispatcher.payment(payment.paymentId());
        long refund =
                jdbc.queryForObject(
                        "SELECT id FROM refund_request WHERE purpose='COMPENSATION'", Long.class);
        // 종료 HTTP보다 보상 접수가 먼저 오면 재시도 상태로 남는다.
        assertThat(dispatcher.refund(refund)).isFalse();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_execution", Long.class))
                .isZero();
        closures.recover();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT acknowledged FROM order_payment_closure", Boolean.class))
                .isTrue();
        jdbc.update(
                "UPDATE payment_delivery_request SET next_action_at=clock_timestamp()-INTERVAL '1 second' WHERE core_refund_request_id=?",
                refund);
        assertThat(deliveries.due(false)).anyMatch(d -> "REFUND".equals(d.kind()));
        dispatcher.refund(refund);
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.RefundExecutionService.class)
                .execute(refund, com.shoppinglive.payment.service.MockRefundScenario.SUCCESS);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(
                        () ->
                                client.refund(refund).status()
                                        == com.shoppinglive.commercecore.payment.entity.RefundStatus
                                                .SUCCESS);
        dispatcher.refund(refund);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM refund_request WHERE id=?",
                                String.class,
                                refund))
                .isEqualTo("SUCCESS");
        assertThat(dispatcher.refund(refund)).isFalse();
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cart_item WHERE id=?", Long.class, cart))
                .isEqualTo(1);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_target_order", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("0원 늦은 성공은 종료를 통지하고 PG 환불과 중복 재고 복구를 만들지 않는다")
    void zeroLateSuccessDoesNotCreatePgRefund() {
        jdbc.update("UPDATE coupon_definition SET fixed_discount=4000 WHERE id=?", COUPON);
        var order = create(1);
        var payment =
                lifecycle.start(
                        MEMBER_A,
                        order.orderNumber(),
                        "zero",
                        0L,
                        COUPON,
                        order.items().getFirst().orderItemId());
        client.start(
                deliveries.read(
                        deliveries.byPayment(payment.paymentId()).command(),
                        com.shoppinglive.commercecore.payment.dto.PaymentCommand.class));
        makeExpired(order);
        dispatcher.payment(payment.paymentId());
        closures.recover();
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request", Long.class)).isZero();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("선점 해제 오류는 만료 상태와 종료 의도까지 함께 롤백한다")
    void expiryAndClosureIntentAreAtomic() {
        var order = create(1);
        start(order, false);
        makeExpired(order);
        doThrow(new IllegalStateException("해제 실패 주입"))
                .when(
                        (SkuService)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        skuSpy))
                .release(501L, 1);
        assertThatThrownBy(() -> lifecycle.expire(groupId(order)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_group", String.class))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_closure", Long.class))
                .isZero();
        assertThat(reserved(501)).isEqualTo(1);
        doCallRealMethod()
                .when(
                        (SkuService)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        skuSpy))
                .release(501L, 1);
        assertThat(lifecycle.expire(groupId(order))).isTrue();
        assertThat(lifecycle.expire(groupId(order))).isFalse();
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("결제 대기 취소는 거절하고 구 API 조회와 결제도 10분 만료를 연장하지 않는다")
    void legacyRoutesRespectNewExpiryAndSharedGuard() {
        var order = create(1);
        assertThatThrownBy(() -> create(1)).isInstanceOf(BusinessException.class);
        start(order, false);
        assertThatThrownBy(() -> lifecycle.cancel(MEMBER_A, order.orderNumber()))
                .isInstanceOf(BusinessException.class);
        makeExpired(order);
        assertThat(legacyGroups.get(MEMBER_A, order.orderNumber()).status().name())
                .isEqualTo("FAILED");
        assertThatThrownBy(() -> legacyGroups.start(MEMBER_A, order.orderNumber(), "legacy"))
                .isInstanceOf(BusinessException.class);
        assertThat(available(501)).isEqualTo(20);
        assertThat(create(1).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("성공 결과와 만료 경합은 만료 주문·선점 해제 한 번·보상 한 건으로 수렴한다")
    void concurrentExpiryAndSuccessAreIdempotent() throws Exception {
        var order = create(1);
        var payment = start(order, false);
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        makeExpired(order);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return lifecycle.expire(groupId(order));
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return dispatcher.payment(payment.paymentId());
                            });
            gate.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_request WHERE purpose='COMPENSATION'",
                                Long.class))
                .isEqualTo(1);
    }
}
