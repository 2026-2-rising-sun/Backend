package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
        properties = {
            "commercecore.refunds.api-enabled=true",
            "commercecore.refunds.execution-enabled=true",
            "commercecore.order.closure-recovery.initial-delay=PT1H"
        })
@AutoConfigureMockMvc
class OrderSheetPostgresTest extends OrderSheetPostgresSupport {
    @MockitoSpyBean SkuService skuSpy;

    @Test
    @DisplayName("여러 SKU 주문서 생성과 재전송은 UUID 주문 하나와 선점 한 번을 보존한다")
    void snapshotsMultipleSkusAndReplaysOnce() {
        String key = "sheet-replay";
        var items =
                List.of(
                        new CreateOrderSheetRequest.Item(501L, 2),
                        new CreateOrderSheetRequest.Item(503L, 1));
        var first = create(key, items);
        var replay = create(key, items.reversed());
        assertThat(UUID.fromString(first.orderNumber()).toString()).isEqualTo(first.orderNumber());
        assertThat(replay).isEqualTo(first);
        assertThat(first.items()).hasSize(2);
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM expires_at-created_at)::BIGINT FROM payment_group",
                                Long.class))
                .isEqualTo(600);
        assertThatThrownBy(() -> create(key, List.of(new CreateOrderSheetRequest.Item(501L, 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("한 SKU가 부족하면 주문과 다른 SKU 선점도 전부 롤백한다")
    void rollsBackEveryItemWhenOneSkuIsShort() {
        jdbc.update("UPDATE sku SET available=0 WHERE id=502");
        assertThatThrownBy(
                        () ->
                                create(
                                        "short",
                                        List.of(
                                                new CreateOrderSheetRequest.Item(501L, 1),
                                                new CreateOrderSheetRequest.Item(502L, 1))))
                .isInstanceOf(BusinessException.class);
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    }

    @Test
    @DisplayName("생성 후 가격·이름·판매 상태가 바뀌어도 저장 가격으로 성공하고 원본 Cart 버전을 보존한다")
    void paymentUsesSnapshotAndPreservesEditedCart() {
        var order = createCart(501, 1);
        long cart = order.items().getFirst().sourceCartItemId();
        jdbc.update("UPDATE sku SET price=9000,sale_status='PRIVATE' WHERE id=501");
        jdbc.update("UPDATE product SET name='변경됨',sale_enabled=FALSE WHERE id=101");
        jdbc.update("UPDATE cart_item SET quantity=2,version=version+1 WHERE id=?", cart);
        var paid = paid(order, false);
        assertThat(paid.items().getFirst().unitPrice()).isEqualTo(4000);
        assertThat(paid.items().getFirst().productName()).isEqualTo("상품101");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT quantity FROM cart_item WHERE id=?", Integer.class, cart))
                .isEqualTo(2);
        assertThat(reserved(501)).isZero();
        assertThat(available(501)).isEqualTo(19);
    }

    @Test
    @DisplayName("판매 준비 상품과 READY SKU는 주문서 생성으로 선점하지 않는다")
    void readinessDoesNotStartTrading() {
        jdbc.update("UPDATE product SET sale_enabled=FALSE WHERE id=101");
        assertThatThrownBy(() -> create(1)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE product SET sale_enabled=TRUE WHERE id=101");
        jdbc.update("UPDATE sku SET sale_status='READY' WHERE id=501");
        assertThatThrownBy(() -> create(1)).isInstanceOf(BusinessException.class);
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("만료 주문 재진입은 FAILED를 반환하고 재선점하거나 기한을 늘리지 않는다")
    void expiredReplayReturnsFreshFailedState() {
        var items = List.of(new CreateOrderSheetRequest.Item(501L, 1));
        var order = create("expired", items);
        makeExpired(order);
        var expires =
                jdbc.queryForObject(
                        "SELECT expires_at FROM payment_group", java.sql.Timestamp.class);
        var replay = create("expired", items);
        assertThat(replay.status()).isEqualTo("FAILED");
        assertThat(replay.failureReason()).isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT expires_at FROM payment_group", java.sql.Timestamp.class))
                .isEqualTo(expires);
    }

    @Test
    @DisplayName("동일 생성 키의 다중 스레드는 하나의 주문과 선점으로 수렴한다")
    void concurrentCreationKeyReservesOnlyOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<OrderSheetResponse>>();
            for (int i = 0; i < 3; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return create(
                                            "concurrent",
                                            List.of(new CreateOrderSheetRequest.Item(501L, 1)));
                                }));
            gate.countDown();
            var numbers = new HashSet<String>();
            for (var result : tasks) numbers.add(result.get(10, TimeUnit.SECONDS).orderNumber());
            assertThat(numbers).hasSize(1);
        }
        assertThat(available(501)).isEqualTo(19);
        assertThat(reserved(501)).isEqualTo(1);
    }

    @Test
    @DisplayName("null 원소·누락 키·금액 합산 초과는 400으로 거절한다")
    void invalidInputNeverBecomesServerError() throws Exception {
        mvc.perform(
                        post("/v1/order-sheets")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "null")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"items\":[null],\"buyerName\":\"이름\",\"buyerPhone\":\"010\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/v1/order-sheets")
                                .header("Authorization", bearer(MEMBER_A))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"items\":[{\"skuId\":501,\"quantity\":1}],\"buyerName\":\"이름\",\"buyerPhone\":\"010\"}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE sku SET price=? WHERE id IN (501,503)", Long.MAX_VALUE);
        mvc.perform(
                        post("/v1/order-sheets")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "overflow")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"items\":[{\"skuId\":501,\"quantity\":1},{\"skuId\":503,\"quantity\":1}],\"buyerName\":\"이름\",\"buyerPhone\":\"010\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("순차 1개씩은 4천·4천·2천원이며 마지막 전량 성공에 쿠폰을 한 번 복원한다")
    void sequentialRefundsFollowApprovedGrossFirstPolicy() {
        var order = paid(create(3), true);
        var amounts = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var r = request(order, 1, "part-" + i).refund();
            amounts.add(r.refundAmount());
            complete(order, r.refundRequestId());
            assertThat(couponStatus()).isEqualTo(i == 2 ? "AVAILABLE" : "USED");
        }
        assertThat(amounts).containsExactly(4000L, 4000L, 2000L);
        assertThat(available(501)).isEqualTo(20);
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("두 개 일괄 환불은 8천원이고 나머지 하나는 2천원이다")
    void batchRefundUsesRemainingPaidCap() {
        var order = paid(create(3), true);
        var first = request(order, 2, "two").refund();
        assertThat(first.refundAmount()).isEqualTo(8000);
        complete(order, first.refundRequestId());
        var last = request(order, 1, "last").refund();
        assertThat(last.refundAmount()).isEqualTo(2000);
        complete(order, last.refundRequestId());
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("환불 실패는 수량·재고를 복구하지 않고 새 키 재시도는 같은 금액을 사용한다")
    void failedRefundDoesNotConsumeQuantityOrMoney() {
        var order = paid(create(3), true);
        var failed = request(order, 1, "failed").refund();
        refundDriver.execute(
                failed.refundRequestId(),
                com.shoppinglive.payment.service.MockRefundScenario.FAILED);
        assertThat(available(501)).isEqualTo(17);
        assertThat(couponStatus()).isEqualTo("USED");
        assertThat(request(order, 1, "failed").created()).isFalse();
        var retry = request(order, 1, "retry").refund();
        assertThat(retry.refundAmount()).isEqualTo(4000);
        complete(order, retry.refundRequestId());
        assertThat(available(501)).isEqualTo(18);
    }

    @Test
    @DisplayName("동시 환불은 미확정 건의 수량과 금액을 중복 사용하지 않는다")
    void concurrentRefundsCannotReuseCapacity() throws Exception {
        var order = paid(create(3), true);
        var accepted = new ArrayList<Long>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 2; i++) {
                String key = "race-" + i;
                results.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        return request(order, 2, key).refund().refundRequestId();
                                    } catch (BusinessException rejected) {
                                        return -1L;
                                    }
                                }));
            }
            gate.countDown();
            for (var result : results) {
                long id = result.get(10, TimeUnit.SECONDS);
                if (id > 0) accepted.add(id);
            }
        }
        assertThat(accepted).hasSize(1);
        assertThat(available(501)).isEqualTo(17);
        complete(order, accepted.getFirst());
        assertThat(available(501)).isEqualTo(19);
        assertThatThrownBy(() -> request(order, 2, "over")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("큰 할인 뒤 0원 잔여 수량도 PG 환불 없이 성공 수량과 재고를 반영한다")
    void zeroRefundRestoresQuantityWithoutCallingPg() {
        jdbc.update("UPDATE coupon_definition SET fixed_discount=8000 WHERE id=?", COUPON);
        var order = paid(create(3), true);
        var first = request(order, 1, "money").refund();
        assertThat(first.refundAmount()).isEqualTo(4000);
        complete(order, first.refundRequestId());
        long before =
                paymentServer.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mock_refund_result", Long.class);
        for (int i = 0; i < 2; i++) {
            var zero = request(order, 1, "zero-" + i).refund();
            assertThat(zero.refundAmount()).isZero();
            complete(order, zero.refundRequestId());
        }
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isEqualTo(before);
        assertThat(available(501)).isEqualTo(20);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("쿠폰 항목만 전량 환불해도 다른 항목이 남으면 쿠폰을 복원하지 않는다")
    void couponItemAloneIsNotTheWholeOrder() {
        var order =
                paid(
                        create(
                                "multi",
                                List.of(
                                        new CreateOrderSheetRequest.Item(501L, 1),
                                        new CreateOrderSheetRequest.Item(502L, 1))),
                        true);
        var couponItem =
                order.items().stream().filter(i -> i.skuId() == 501L).findFirst().orElseThrow();
        var first =
                refunds.request(
                                MEMBER_A,
                                order.orderNumber(),
                                "one",
                                new OrderSheetRefundRequest(
                                        List.of(
                                                new OrderSheetRefundRequest.Item(
                                                        couponItem.orderItemId(), 1))))
                        .refund();
        complete(order, first.refundRequestId());
        assertThat(couponStatus()).isEqualTo("USED");
        var other = order.items().stream().filter(i -> i.skuId() == 502L).findFirst().orElseThrow();
        var last =
                refunds.request(
                                MEMBER_A,
                                order.orderNumber(),
                                "other",
                                new OrderSheetRefundRequest(
                                        List.of(
                                                new OrderSheetRefundRequest.Item(
                                                        other.orderItemId(), 1))))
                        .refund();
        complete(order, last.refundRequestId());
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("만료 쿠폰은 원래 유효기간 그대로 복원하고 중복 결과가 후속 사용을 덮어쓰지 않는다")
    void couponRestorationPreservesExpiryAndLaterUse() {
        var order = paid(create(1), true);
        var expires =
                jdbc.queryForObject(
                        "SELECT expires_at FROM coupon_definition WHERE id=?",
                        java.sql.Timestamp.class,
                        COUPON);
        var refund = request(order, 1, "whole").refund();
        complete(order, refund.refundRequestId());
        var reused = paid(create(1), true);
        assertThat(refundDriver.execute(refund.refundRequestId())).isFalse();
        assertThat(couponStatus()).isEqualTo("USED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT used_payment_group_id FROM member_coupon WHERE coupon_id=?",
                                Long.class,
                                COUPON))
                .isEqualTo(groupId(reused));
        jdbc.update(
                "UPDATE coupon_definition SET starts_at=clock_timestamp()-INTERVAL '3 days',ends_at=clock_timestamp()-INTERVAL '2 days',expires_at=clock_timestamp()-INTERVAL '1 day' WHERE id=?",
                COUPON);
        var expired =
                jdbc.queryForObject(
                        "SELECT expires_at FROM coupon_definition WHERE id=?",
                        java.sql.Timestamp.class,
                        COUPON);
        var next = request(reused, 1, "expired-coupon").refund();
        complete(reused, next.refundRequestId());
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT expires_at FROM coupon_definition WHERE id=?",
                                java.sql.Timestamp.class,
                                COUPON))
                .isEqualTo(expired)
                .isBefore(expires);
    }

    @Test
    @DisplayName("환불 적용 중 재고 오류는 결과·수량을 롤백하고 같은 성공 결과로 회복한다")
    void refundStockFailureRollsBackApplication() {
        var order = paid(create(1), false);
        var r = request(order, 1, "atomic").refund();
        doThrow(new IllegalStateException("복구 실패 주입")).when(skuSpy).restock(501L, 1);
        assertThatThrownBy(() -> complete(order, r.refundRequestId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(refunds.get(MEMBER_A, order.orderNumber(), r.refundRequestId()).status())
                .isEqualTo("PROCESSING");
        assertThat(available(501)).isEqualTo(19);
        doCallRealMethod().when(skuSpy).restock(501L, 1);
        complete(order, r.refundRequestId());
        assertThat(available(501)).isEqualTo(20);
    }
}
