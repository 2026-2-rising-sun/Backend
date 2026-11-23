package com.shoppinglive.commercecore.order.service;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.relay.*;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;

@SpringBootTest(
        properties = {
            "commerce.commission-rate=0.1250",
            "commercecore.product.lookup.mode=local",
            "commercecore.refunds.api-enabled=true"
        })
@AutoConfigureMockMvc
class KafkaRefundPostgresTest extends OrderSheetPostgresSupport {
    @Autowired PaymentRelayService relay;
    @Autowired PaymentRelayDispatcher relayDispatcher;
    @Autowired KafkaRefundService refunds;
    @Autowired RefundQueryService queries;
    @Autowired RefundIntentStore intents;
    @Autowired PurchaseGuard guard;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @MockitoBean PaymentRelayClient remote;
    @MockitoSpyBean EventOutbox outbox;
    @MockitoSpyBean OrderTimeSource time;
    static final long PAYMENT = 7001L;

    String prepare(boolean coupon) {
        String number =
                relay.create(
                                MEMBER_A,
                                UUID.randomUUID().toString(),
                                new RelayOrderRequest(
                                        List.of(new CreateOrderSheetRequest.Item(501L, 3)),
                                        null,
                                        "구매자",
                                        "01012345678",
                                        coupon ? 10000L : 12000L,
                                        coupon ? COUPON : null,
                                        coupon ? 501L : null))
                        .number();
        var state = relay.state(MEMBER_A, number);
        relay.prepared(
                deliveries.relay(id(number), "PREPARE"),
                new Created(PAYMENT, "CREATED", number + "-1", state.amount(), state.expiresAt()));
        return number;
    }

    PaymentDeliveryStore.Delivery accept(String number) {
        return relay.acceptApproval(
                MEMBER_A,
                number,
                "approve",
                new Approval(
                        MEMBER_A, "key", number + "-1", relay.state(MEMBER_A, number).amount()));
    }

    String paid(boolean coupon) {
        String n = prepare(coupon);
        accept(n);
        relay.approved(new Approved(PAYMENT, n, relay.state(MEMBER_A, n).amount(), Instant.now()));
        return n;
    }

    long id(String number) {
        return jdbc.queryForObject(
                "SELECT id FROM payment_group WHERE group_number=?", Long.class, number);
    }

    long item(String number) {
        return jdbc.queryForObject(
                "SELECT id FROM orders WHERE payment_group_id=?", Long.class, id(number));
    }

    RefundSelection all() {
        return RefundSelection.parse(null);
    }

    RefundSelection quantity(String n, int q) {
        return new RefundSelection(
                "ITEMS", List.of(new RefundSelection.Item(item(n), q)), List.of(), null);
    }

    String requestId(String n) {
        return jdbc.queryForObject(
                "SELECT refund_request_id FROM core_refund_intent WHERE payment_group_id=? ORDER BY user_request_id DESC NULLS LAST LIMIT 1",
                String.class,
                id(n));
    }

    ObjectNode result(String request, String status, long version, String execution)
            throws Exception {
        var payload =
                (ObjectNode)
                        mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT request_payload FROM core_refund_intent WHERE refund_request_id=?",
                                        String.class,
                                        request));
        payload.put("executionId", execution)
                .put("resultVersion", version)
                .put("status", status)
                .put(
                        "refundedAmount",
                        status.equals("SUCCEEDED") ? payload.path("amount").longValue() : 0);
        if (status.equals("UNKNOWN")) {
            payload.putNull("resolvedAt");
            payload.put("nextActionAt", Instant.now().plusSeconds(60).toString());
        } else {
            payload.put("resolvedAt", Instant.now().toString());
            payload.putNull("nextActionAt");
        }
        if (Set.of("FAILED", "NO_CHARGE").contains(status)) payload.put("failureCode", status);
        else payload.putNull("failureCode");
        ObjectNode event = mapper.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString())
                .put("eventType", "RefundResultRecorded")
                .put("schemaVersion", 1)
                .put("source", "payment-service")
                .put("aggregateId", payload.path("orderRef").asText())
                .put("occurredAt", Instant.now().toString());
        event.set("payload", payload);
        return event;
    }

    void requestSample(String number, String name) throws Exception {
        String envelope =
                jdbc.queryForObject(
                        "SELECT payload FROM event_outbox WHERE topic='commerce.refund.requests.v1' AND message_key=? ORDER BY created_at DESC LIMIT 1",
                        String.class,
                        number);
        var path = java.nio.file.Path.of("build/contract-samples/" + name + ".json");
        java.nio.file.Files.createDirectories(path.getParent());
        mapper.writeValue(
                path.toFile(),
                Map.of(
                        "contract",
                        "commerce-refund-requests.yaml",
                        "message",
                        "RefundRequested",
                        "payload",
                        mapper.readTree(envelope)));
    }

    boolean apply(ObjectNode event) {
        return refunds.result(event.path("aggregateId").asText(), event);
    }

    void success(String n) throws Exception {
        apply(result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString()));
    }

    String state(String n) {
        return groups.findById(id(n)).orElseThrow().getStatus().name();
    }

    @Test
    @DisplayName("부분 수량은 할인 전 단가부터 환불하고 전수량 성공 때만 쿠폰을 복원한다")
    void partialQuantitiesAndCoupon() throws Exception {
        String n = paid(true);
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var r = refunds.request(MEMBER_A, n, "part-" + i, quantity(n, 1));
            amounts.add(r.refund().refundAmount());
            requestSample(n, "UserRefundRequested");
            assertThat(r.refund().targets().getFirst().quantity()).isEqualTo(1);
            assertThat(state(n)).isEqualTo("CANCEL_PENDING");
            assertThat(available(501)).isEqualTo(17 + i);
            success(n);
            assertThat(state(n)).isEqualTo(i == 2 ? "REFUNDED" : "PARTIALLY_REFUNDED");
            assertThat(couponStatus()).isEqualTo(i == 2 ? "AVAILABLE" : "USED");
        }
        assertThat(amounts).containsExactly(4000L, 4000L, 2000L);
        assertThat(available(501)).isEqualTo(20);
        assertThat(queries.refundableTargets(MEMBER_A, n).getFirst().succeededQuantity())
                .isEqualTo(3);
        assertThat(refunds.request(MEMBER_A, n, "part-0", quantity(n, 1)).created()).isFalse();
    }

    @Test
    @DisplayName("전체 잔여 환불은 이미 성공한 수량을 제외하고 새 키의 미완료 접수를 직렬화한다")
    void fullRemainingAndSerialization() throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "first", quantity(n, 1));
        assertThatThrownBy(() -> refunds.request(MEMBER_A, n, "second", all()))
                .isInstanceOf(BusinessException.class);
        success(n);
        var r = refunds.request(MEMBER_A, n, "remaining", all());
        assertThat(r.refund().targets().getFirst().quantity()).isEqualTo(2);
        success(n);
        assertThat(state(n)).isEqualTo("REFUNDED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("확정 실패는 이전 부분환불 상태로 복귀하고 재고·쿠폰을 변경하지 않는다")
    void failedRestoresPreviousPartialState() throws Exception {
        String n = paid(true);
        refunds.request(MEMBER_A, n, "one", quantity(n, 1));
        success(n);
        refunds.request(MEMBER_A, n, "two", quantity(n, 1));
        apply(result(requestId(n), "FAILED", 1, UUID.randomUUID().toString()));
        assertThat(state(n)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(available(501)).isEqualTo(18);
        assertThat(couponStatus()).isEqualTo("USED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, item(n)))
                .isEqualTo("PARTIALLY_REFUNDED");
        refunds.request(MEMBER_A, n, "retry", quantity(n, 2));
    }

    @Test
    @DisplayName("미확정 결과는 예약을 유지하고 역순·중복 결과는 성공 재고를 다시 복원하지 않는다")
    void unknownThenSuccessWithReverseDelivery() throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "unknown", quantity(n, 1));
        String execution = UUID.randomUUID().toString();
        var unknown = result(requestId(n), "UNKNOWN", 1, execution);
        apply(unknown);
        assertThat(state(n)).isEqualTo("CANCEL_PENDING");
        assertThat(queries.refundableTargets(MEMBER_A, n).getFirst().heldQuantity()).isEqualTo(1);
        var success = result(requestId(n), "SUCCEEDED", 2, execution);
        assertThat(apply(success)).isTrue();
        assertThat(apply(success)).isFalse();
        assertThat(apply(unknown)).isFalse();
        assertThat(available(501)).isEqualTo(18);
        ((ObjectNode) success.path("payload")).put("refundedAmount", 1);
        assertThatThrownBy(() -> apply(success)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount",
                "paymentId",
                "purpose",
                "orderRef",
                "reason",
                "requestedAt",
                "currency"
            })
    @DisplayName("환불 결과 원문이 다르면 요청·주문·자원 반영 없이 거절한다")
    void rejectsOriginalMismatch(String field) throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "mismatch", all());
        var event = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        ((ObjectNode) event.path("payload")).put(field, "wrong");
        assertThatThrownBy(() -> apply(event)).isInstanceOf(BusinessException.class);
        assertThat(state(n)).isEqualTo("CANCEL_PENDING");
        assertThat(available(501)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_result_event", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("같은 키 동시 접수는 원문·요청·Outbox를 한 번만 저장한다")
    void concurrentSameKey() throws Exception {
        String n = paid(false);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return refunds.request(MEMBER_A, n, "same", all())
                                            .refund()
                                            .id();
                                }));
            assertThat(tasks.getFirst().get(20, TimeUnit.SECONDS))
                    .isEqualTo(tasks.getLast().get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_intent", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE topic='commerce.refund.requests.v1'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Outbox 저장 실패는 사용자 취소 대기와 환불 원문을 함께 롤백한다")
    void outboxFailureRollsBackIntake() {
        String n = paid(false);
        tx.executeWithoutResult(
                t ->
                        doThrow(new IllegalStateException("Outbox 장애"))
                                .when(outbox)
                                .append(eq("commerce.refund.requests.v1"), any()));
        try {
            assertThatThrownBy(() -> refunds.request(MEMBER_A, n, "rollback", all()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            reset(outbox);
        }
        assertThat(state(n)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request", Long.class)).isZero();
    }

    @Test
    @DisplayName("승인 응답 유실은 별도 보상을 기록하고 늦은 승인·만료·재전달로 주문을 성공시키지 않는다")
    void lostApprovalFencesLateSuccessAndExpiry() throws Exception {
        String n = prepare(true);
        var delivery = accept(n);
        relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
        requestSample(n, "SystemRefundRequested");
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(intents.compensation(id(n)).status()).isEqualTo("REQUESTED");
        jdbc.update(
                "UPDATE payment_group SET processing_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                id(n));
        assertThat(lifecycle.expire(id(n))).isFalse();
        relayDispatcher.dispatch(delivery);
        verifyNoInteractions(remote);
        relay.approved(new Approved(PAYMENT, n, 10000, Instant.now()));
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(available(501)).isEqualTo(17);
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        String request = requestId(n);
        var done = result(request, "SUCCEEDED", 1, UUID.randomUUID().toString());
        apply(done);
        apply(done);
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(intents.compensation(id(n)).outcome()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("미승인 종료 결과는 금전 환불 없이 예약을 단일 해제하고 종료 주문은 유지한다")
    void noChargeAndAlreadyClosed() throws Exception {
        String n = prepare(true);
        var delivery = accept(n);
        relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
        var done = result(requestId(n), "NO_CHARGE", 1, UUID.randomUUID().toString());
        apply(done);
        apply(done);
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        relay.approved(new Approved(PAYMENT, n, 10000, Instant.now()));
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("만료 후 늦은 승인의 보상 완료는 종료 상태와 이미 반환한 자원을 유지한다")
    void expiredCompensationDoesNotRestockTwice() throws Exception {
        String n = prepare(true);
        jdbc.update(
                "UPDATE payment_group SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                id(n));
        lifecycle.expire(id(n));
        relay.approved(new Approved(PAYMENT, n, 10000, Instant.now()));
        apply(result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString()));
        assertThat(state(n)).isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("승인과 보상 intent 경합은 같은 그룹 잠금으로 정상성공 또는 보상 중 하나에 수렴한다")
    void approvalCompensationRace() throws Exception {
        String n = prepare(false);
        var delivery = accept(n);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                relay.approved(new Approved(PAYMENT, n, 12000, Instant.now()));
                                return true;
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
                                return true;
                            });
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        if (intents.fenced(id(n))) {
            assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
            assertThat(reserved(501)).isEqualTo(3);
        } else {
            assertThat(state(n)).isEqualTo("PAID");
            assertThat(reserved(501)).isZero();
        }
    }

    @Test
    @DisplayName("승인 HTTP 응답 유실은 처리기한까지 기다리고 기한 도달 뒤 보상·송신차단한다")
    void dispatcherRecordsTransportLossOnce() {
        String n = prepare(false);
        var delivery = accept(n);
        when(remote.get(n))
                .thenReturn(
                        new Reply<>(
                                200,
                                true,
                                new Lookup(PAYMENT, "CREATED", 12000, 0, n + "-1", null),
                                null,
                                null));
        when(remote.approve(eq(n), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("승인 응답 유실"));
        relayDispatcher.dispatch(delivery);
        assertThat(intents.fenced(id(n))).isFalse();
        assertThat(state(n)).isEqualTo("PAYMENT_CONFIRMING");
        jdbc.update(
                "UPDATE payment_group SET processing_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                id(n));
        relayDispatcher.dispatch(delivery);
        verify(remote, times(1)).approve(eq(n), any());
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(intents.compensation(id(n)).status()).isEqualTo("REQUESTED");
        assertThat(reserved(501)).isEqualTo(3);
    }

    @Test
    @DisplayName("정상202 처리중 응답은 시스템 취소 의도가 아니며 뒤 승인 사실을 정상 반영한다")
    void acceptedProcessingIsNotResponseLoss() {
        String n = prepare(false);
        var delivery = accept(n);
        when(remote.get(n))
                .thenReturn(
                        new Reply<>(
                                200,
                                true,
                                new Lookup(PAYMENT, "CREATED", 12000, 0, n + "-1", null),
                                null,
                                null));
        when(remote.approve(eq(n), any()))
                .thenReturn(
                        new Reply<>(
                                202,
                                true,
                                new ApprovalResult(PAYMENT, "PENDING", null, null),
                                null,
                                null));
        relayDispatcher.dispatch(delivery);
        assertThat(intents.fenced(id(n))).isFalse();
        relay.approved(new Approved(PAYMENT, n, 12000, Instant.now()));
        assertThat(state(n)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("선택한 항목만 취소 대기로 바꾸고 소유권·예상금액·잔여수량을 접수 시 재검증한다")
    void selectedItemValidationAndOwnership() {
        String n =
                relay.create(
                                MEMBER_A,
                                "two-items",
                                new RelayOrderRequest(
                                        List.of(
                                                new CreateOrderSheetRequest.Item(501L, 2),
                                                new CreateOrderSheetRequest.Item(502L, 1)),
                                        null,
                                        "구매자",
                                        "01012345678",
                                        11000L,
                                        null,
                                        null))
                        .number();
        var st = relay.state(MEMBER_A, n);
        relay.prepared(
                deliveries.relay(id(n), "PREPARE"),
                new Created(PAYMENT, "CREATED", n + "-1", 11000, st.expiresAt()));
        accept(n);
        relay.approved(new Approved(PAYMENT, n, 11000, Instant.now()));
        long chosen =
                jdbc.queryForObject(
                        "SELECT id FROM orders WHERE payment_group_id=? AND sku_id=501",
                        Long.class,
                        id(n));
        assertThatThrownBy(() -> refunds.request(MEMBER_B, n, "owner", all()))
                .isInstanceOf(BusinessException.class);
        var bad =
                new RefundSelection(
                        "ITEMS", List.of(new RefundSelection.Item(chosen, 1)), List.of(), 3999L);
        assertThatThrownBy(() -> refunds.request(MEMBER_A, n, "bad-amount", bad))
                .isInstanceOf(BusinessException.class);
        var selection =
                new RefundSelection(
                        "ITEMS", List.of(new RefundSelection.Item(chosen, 1)), List.of(), 4000L);
        refunds.request(MEMBER_A, n, "selected", selection);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE payment_group_id=? AND sku_id=502",
                                String.class,
                                id(n)))
                .isEqualTo("PAID");
        assertThatThrownBy(
                        () ->
                                refunds.request(
                                        MEMBER_A,
                                        n,
                                        "selected",
                                        new RefundSelection(
                                                "ITEMS",
                                                List.of(new RefundSelection.Item(chosen, 2)),
                                                List.of(),
                                                4000L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("정확168시간부터 신규 환불을 거절하지만 기존 키는 기간 뒤에도 재조회한다")
    void refundWindowAndReplay() {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "old-key", quantity(n, 1));
        jdbc.update(
                "UPDATE order_payment_relay SET approved_at=CURRENT_TIMESTAMP-INTERVAL '168 hours' WHERE payment_group_id=?",
                id(n));
        assertThat(refunds.request(MEMBER_A, n, "old-key", quantity(n, 1)).created()).isFalse();
        assertThatThrownBy(() -> refunds.request(MEMBER_A, n, "new-key", all()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("결과 항목 변조·사용자 미승인 종료·같은 버전 충돌은 상태를 바꾸지 않는다")
    void rejectsMismatchedResultTargetsAndStates() throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "result-validation", all());
        String request = requestId(n), execution = UUID.randomUUID().toString();
        var invalid = result(request, "SUCCEEDED", 1, execution);
        ((ObjectNode) invalid.path("payload").path("items").get(0).path("orderItems").get(0))
                .put("quantity", 1);
        assertThatThrownBy(() -> apply(invalid)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> apply(result(request, "NO_CHARGE", 1, execution)))
                .isInstanceOf(BusinessException.class);
        var unknown = result(request, "UNKNOWN", 1, execution);
        apply(unknown);
        assertThatThrownBy(() -> apply(result(request, "SUCCEEDED", 1, execution)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () -> apply(result(request, "SUCCEEDED", 2, UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class);
        var success = result(request, "SUCCEEDED", 2, execution);
        apply(success);
        assertThatThrownBy(() -> apply(result(request, "UNKNOWN", 3, execution)))
                .isInstanceOf(BusinessException.class);
        var repeated = result(request, "SUCCEEDED", 3, execution);
        assertThat(apply(repeated)).isFalse();
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("시스템 보상 실패·미확정은 주문 예약을 유지하고 사용자 취소 대기로 바꾸지 않는다")
    void systemFailedAndUnknownKeepReservations() throws Exception {
        String n = prepare(true);
        relay.approvalUnknown(accept(n), Map.of("code", "UNKNOWN"));
        String request = requestId(n), execution = UUID.randomUUID().toString();
        apply(result(request, "UNKNOWN", 1, execution));
        assertThat(intents.compensation(id(n)).status()).isEqualTo("UNKNOWN");
        apply(result(request, "FAILED", 2, execution));
        assertThat(intents.compensation(id(n)).status()).isEqualTo("FAILED");
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(lifecycle.expire(id(n))).isFalse();
    }

    @Test
    @DisplayName("결과 반영 Outbox 장애는 성공·재고반환·요청상태를 롤백하고 재수신으로 한 번 복구한다")
    void resultCommitFailureIsRetryable() throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "result-rollback", all());
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        tx.executeWithoutResult(
                t ->
                        doThrow(new IllegalStateException("알림 Outbox 장애"))
                                .when(outbox)
                                .append(eq("commerce.payment-group.v1"), any()));
        try {
            assertThatThrownBy(() -> apply(done)).isInstanceOf(IllegalStateException.class);
        } finally {
            reset(outbox);
        }
        assertThat(state(n)).isEqualTo("CANCEL_PENDING");
        assertThat(available(501)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_result_event", Long.class))
                .isZero();
        apply(done);
        assertThat(state(n)).isEqualTo("REFUNDED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("서로 다른 키의 동시 부분환불은 한 건만 접수하고 나머지는 충돌한다")
    void concurrentDifferentKeysSerialize() throws Exception {
        String n = paid(false);
        var selection = quantity(n, 1);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "parallel-" + i;
                calls.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        refunds.request(MEMBER_A, n, key, selection);
                                        return true;
                                    } catch (BusinessException conflict) {
                                        return false;
                                    }
                                }));
            }
            assertThat(
                            List.of(
                                    calls.getFirst().get(20, TimeUnit.SECONDS),
                                    calls.getLast().get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_intent", Long.class))
                .isEqualTo(1);
        assertThat(available(501)).isEqualTo(17);
    }

    @Test
    @DisplayName("명시적 승인 HTTP 인증 거절은 응답 유실 보상을 생성하지 않는다")
    void rejectedHttpIsNotUnknownExecution() {
        String n = prepare(false);
        var delivery = accept(n);
        when(remote.get(n))
                .thenReturn(
                        new Reply<>(
                                200,
                                true,
                                new Lookup(PAYMENT, "CREATED", 12000, 0, n + "-1", null),
                                null,
                                null));
        when(remote.approve(eq(n), any()))
                .thenReturn(new Reply<>(401, false, null, "UNAUTHORIZED", null));
        relayDispatcher.dispatch(delivery);
        assertThat(intents.fenced(id(n))).isFalse();
        assertThat(state(n)).isEqualTo("PAYMENT_CONFIRMING");
    }

    @Test
    @DisplayName("조회 중 보상이 먼저 커밋하면 승인 송신 시작 표시와 HTTP를 모두 차단한다")
    void compensationDuringLookupBlocksSendClaim() {
        String n = prepare(false);
        var delivery = accept(n);
        when(remote.get(n))
                .thenAnswer(
                        call -> {
                            relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
                            return new Reply<>(
                                    200,
                                    true,
                                    new Lookup(PAYMENT, "CREATED", 12000, 0, n + "-1", null),
                                    null,
                                    null);
                        });
        relayDispatcher.dispatch(delivery);
        verify(remote, never()).approve(anyString(), any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approval_send_started_at FROM order_payment_relay WHERE payment_group_id=?",
                                java.sql.Timestamp.class,
                                id(n)))
                .isNull();
        assertThat(intents.fenced(id(n))).isTrue();
    }

    @Test
    @DisplayName("원결제ID 없는 시스템 미승인 종료도 알림·가짜ID 없이 예약을 단일 해제한다")
    void noChargeWithoutPaymentIdDoesNotInventNotification() throws Exception {
        String n =
                relay.create(
                                MEMBER_A,
                                "no-payment",
                                new RelayOrderRequest(
                                        List.of(new CreateOrderSheetRequest.Item(501L, 3)),
                                        null,
                                        "구매자",
                                        "01012345678",
                                        10000L,
                                        COUPON,
                                        501L))
                        .number();
        tx.executeWithoutResult(
                t -> {
                    guard.lock(MEMBER_A);
                    var group = groups.lockById(id(n)).orElseThrow();
                    refunds.compensateLocked(group, "APPROVAL_UNKNOWN");
                });
        String request = requestId(n);
        var done = result(request, "NO_CHARGE", 1, UUID.randomUUID().toString());
        assertThat(done.path("payload").has("paymentId")).isFalse();
        apply(done);
        apply(done);
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(intents.compensation(id(n)).outcome()).isEqualTo("NO_CHARGE");
        assertThat(relay.state(MEMBER_A, n).paymentId()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type IN ('PaymentFailed','OrderFailed')",
                                Long.class))
                .isZero();
    }

    @Test
    @DisplayName("늦은 승인 뒤 시스템 환불 완료는 승인 사실을 보존하고 결제실패 알림을 발행하지 않는다")
    void systemCompletionDoesNotPublishRejectedPayment() throws Exception {
        String n = prepare(true);
        relay.approvalUnknown(accept(n), Map.of("code", "UNKNOWN"));
        relay.approved(new Approved(PAYMENT, n, 10000, Instant.now()));
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        apply(done);
        apply(done);
        assertThat(relay.state(MEMBER_A, n).paymentStatus()).isEqualTo("APPROVED");
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type IN ('PaymentFailed','OrderFailed')",
                                Long.class))
                .isZero();
    }

    @Test
    @DisplayName("정상 결제 거절의 기존 알림은 REJECTED 사유로 한 번 유지한다")
    void normalDeclineAndUserRefundKeepNotifications() throws Exception {
        String n = prepare(false);
        var delivery = accept(n);
        relay.declined(delivery, Map.of("status", "DECLINED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='PaymentFailed'",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        mapper.readTree(
                                        jdbc.queryForObject(
                                                "SELECT payload FROM event_outbox WHERE event_type='PaymentFailed'",
                                                String.class))
                                .path("payload")
                                .path("failureCode")
                                .asText())
                .isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("사용자 환불 성공의 기존 알림은 중복 결과에서도 한 번 유지한다")
    void userRefundKeepsNotification() throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "user-notification", all());
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        apply(done);
        apply(done);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundCompleted'",
                                Long.class))
                .isEqualTo(1);
    }

    void replaceStoredRequestId(String n, String id) throws Exception {
        String previous = requestId(n);
        var request =
                (ObjectNode)
                        mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT request_payload FROM core_refund_intent WHERE refund_request_id=?",
                                        String.class,
                                        previous));
        request.put("refundRequestId", id);
        tx.executeWithoutResult(
                t -> {
                    jdbc.update(
                            "UPDATE core_refund_intent SET refund_request_id=?,request_payload=? WHERE refund_request_id=?",
                            id,
                            request.toString(),
                            previous);
                    jdbc.update(
                            "UPDATE event_outbox SET payload=REPLACE(payload,?,?) WHERE topic='commerce.refund.requests.v1' AND message_key=?",
                            previous,
                            id,
                            n);
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "late-7001",
                "a",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    @DisplayName("계약상 허용된 저장 요청ID는 UUID가 아니어도 결과를 한 번 적용한다")
    void acceptsContractRequestIdsAndDuplicate(String allowed) throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "allowed-id", all());
        replaceStoredRequestId(n, allowed);
        var done = result(allowed, "SUCCEEDED", 1, UUID.randomUUID().toString());
        assertThat(apply(done)).isTrue();
        assertThat(apply(done)).isFalse();
        assertThat(state(n)).isEqualTo("REFUNDED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(queries.list(MEMBER_A, n).getFirst().refundRequestId()).isEqualTo(allowed);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"\"",
                "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
                "\"white space\"",
                "\"!\"",
                "1",
                "null"
            })
    @DisplayName("빈값·65자·공백·허용외문자·비문자열 요청ID는 원문 조회 전에 거절한다")
    void rejectsInvalidContractRequestIds(String idJson) throws Exception {
        String n = paid(false);
        refunds.request(MEMBER_A, n, "invalid-id", all());
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        ((ObjectNode) done.path("payload")).set("refundRequestId", mapper.readTree(idJson));
        assertThatThrownBy(() -> apply(done))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("환불 요청 ID");
        assertThat(state(n)).isEqualTo("CANCEL_PENDING");
        assertThat(available(501)).isEqualTo(17);
    }

    long compensationRequests(String n) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE topic='commerce.refund.requests.v1' AND message_key=? AND event_type='RefundRequested'",
                Long.class,
                n);
    }

    @Test
    @DisplayName("첫 처리 시작599초는 예약을 유지하고600초에 그룹·항목·고정원문을 함께 보상대기로 바꾼다")
    void processingDeadlineHasExactBoundaryAndDoesNotExtend() throws Exception {
        String n = prepare(true);
        var delivery = accept(n);
        Instant deadline = groups.findById(id(n)).orElseThrow().getProcessingExpiresAt();
        doReturn(deadline.minusSeconds(1)).when(time).now();
        assertThat(lifecycle.expire(id(n))).isFalse();
        assertThat(state(n)).isEqualTo("PAYMENT_CONFIRMING");
        assertThat(compensationRequests(n)).isZero();
        assertThat(accept(n).requestId()).isEqualTo(delivery.requestId());
        relay.pending(delivery, Map.of("code", "TRANSPORT_UNKNOWN"), null);
        assertThat(groups.findById(id(n)).orElseThrow().getProcessingExpiresAt())
                .isEqualTo(deadline);
        doReturn(deadline).when(time).now();
        assertThat(lifecycle.expire(id(n))).isTrue();
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM orders WHERE payment_group_id=?",
                                String.class,
                                id(n)))
                .containsOnly("COMPENSATION_PENDING");
        assertThat(compensationRequests(n)).isEqualTo(1);
        String request = requestId(n);
        assertThat(lifecycle.expire(id(n))).isFalse();
        relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
        assertThat(requestId(n)).isEqualTo(request);
        assertThat(compensationRequests(n)).isEqualTo(1);
        var outgoing =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT request_payload FROM core_refund_intent WHERE refund_request_id=?",
                                String.class,
                                request));
        assertThat(outgoing.path("purpose").asText()).isEqualTo("SYSTEM_COMPENSATION");
        assertThat(outgoing.path("orderRef").asText()).isEqualTo(n);
        assertThat(available(501)).isEqualTo(17);
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(relay.view(MEMBER_A, n).order().status()).isEqualTo("COMPENSATION_PENDING");
        assertThat(relay.view(MEMBER_A, n).order().items())
                .allSatisfy(item -> assertThat(item.status()).isEqualTo("COMPENSATION_PENDING"));
        assertThat(relay.active(MEMBER_A)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("인증조회 또는 승인 응답의 명시적UNKNOWN은600초 전 즉시 보상대기로 전환한다")
    void explicitUnknownImmediatelyCompensates(boolean lookupUnknown) {
        String n = prepare(true);
        var delivery = accept(n);
        when(remote.get(n))
                .thenReturn(
                        new Reply<>(
                                200,
                                true,
                                new Lookup(
                                        PAYMENT,
                                        lookupUnknown ? "UNKNOWN" : "CREATED",
                                        10000,
                                        0,
                                        n + "-1",
                                        null),
                                null,
                                null));
        if (!lookupUnknown)
            when(remote.approve(eq(n), any()))
                    .thenReturn(
                            new Reply<>(
                                    202,
                                    true,
                                    new ApprovalResult(PAYMENT, "UNKNOWN", null, null),
                                    null,
                                    null));
        relayDispatcher.dispatch(delivery);
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(compensationRequests(n)).isEqualTo(1);
        assertThat(reserved(501)).isEqualTo(3);
        relayDispatcher.dispatch(delivery);
        verify(remote, times(1)).get(n);
        verify(remote, times(lookupUnknown ? 0 : 1)).approve(eq(n), any());
    }

    @Test
    @DisplayName("상태전이 뒤 Outbox 장애는 그룹·항목·의도·원문을 전부 롤백하고 같은 경계 재시도로 복구한다")
    void compensationOutboxFailureRollsBackStateAndIntent() {
        String n = prepare(true);
        accept(n);
        doReturn(groups.findById(id(n)).orElseThrow().getProcessingExpiresAt()).when(time).now();
        tx.executeWithoutResult(
                t ->
                        doThrow(new IllegalStateException("보상 Outbox 장애"))
                                .when(outbox)
                                .append(eq("commerce.refund.requests.v1"), any()));
        try {
            assertThatThrownBy(() -> lifecycle.expire(id(n)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(state(n)).isEqualTo("PAYMENT_CONFIRMING");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT status FROM orders WHERE payment_group_id=?",
                                    String.class,
                                    id(n)))
                    .containsOnly("PAYMENT_CONFIRMING");
            assertThat(intents.fenced(id(n))).isFalse();
            assertThat(compensationRequests(n)).isZero();
            assertThat(reserved(501)).isEqualTo(3);
            assertThat(couponStatus()).isEqualTo("RESERVED");
        } finally {
            tx.executeWithoutResult(
                    t -> doCallRealMethod().when(outbox).append(anyString(), any()));
        }
        assertThat(lifecycle.expire(id(n))).isTrue();
        assertThat(compensationRequests(n)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 만료와UNKNOWN은 회원·그룹 잠금으로 요청1개와 예약보존에 수렴한다")
    void concurrentDeadlineAndUnknownCreateOneIntent() throws Exception {
        String n = prepare(true);
        var delivery = accept(n);
        doReturn(groups.findById(id(n)).orElseThrow().getProcessingExpiresAt()).when(time).now();
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return lifecycle.expire(id(n));
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
                                return true;
                            });
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(compensationRequests(n)).isEqualTo(1);
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("보상대기는 승인·결제창·일반만료·사용자취소·새구매 우회를 막고 늦은승인은 사실만 보존한다")
    void compensationPendingCannotBeBypassed() {
        String n = prepare(true);
        var delivery = accept(n);
        relay.approvalUnknown(delivery, Map.of("code", "UNKNOWN"));
        assertThatThrownBy(() -> accept(n)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> lifecycle.cancel(MEMBER_A, n))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> relayDispatcher.checkout(MEMBER_A, n))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> prepare(false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> refunds.request(MEMBER_A, n, "no-user-refund", all()))
                .isInstanceOf(BusinessException.class);
        assertThat(lifecycle.expire(id(n))).isFalse();
        assertThat(relay.claimApprovalSend(delivery)).isFalse();
        relayDispatcher.dispatch(delivery);
        verifyNoInteractions(remote);
        relay.approved(new Approved(PAYMENT, n, 10000, Instant.now()));
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(reserved(501)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "NO_CHARGE"})
    @DisplayName("보상실패는 취소완료가 아니며 같은실행의 검증된상위버전 완료만 CANCELLED와 자원단일해제를 확정한다")
    void failedSystemResultCanConvergeWithoutNewRequest(String finalStatus) throws Exception {
        String n = prepare(true);
        relay.approvalUnknown(accept(n), Map.of("code", "UNKNOWN"));
        String request = requestId(n), execution = UUID.randomUUID().toString();
        var unknown = result(request, "UNKNOWN", 1, execution);
        var failed = result(request, "FAILED", 2, execution);
        apply(unknown);
        apply(failed);
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(intents.compensation(id(n)).completedAt()).isNull();
        var done = result(request, finalStatus, 3, execution);
        apply(done);
        assertThat(apply(done)).isFalse();
        assertThat(apply(failed)).isFalse();
        assertThat(apply(unknown)).isFalse();
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM orders WHERE payment_group_id=?",
                                String.class,
                                id(n)))
                .containsOnly("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(requestId(n)).isEqualTo(request);
        assertThat(compensationRequests(n)).isEqualTo(1);
        assertThat(intents.compensation(id(n)).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> apply(result(request, "FAILED", 4, execution)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("원결제ID 없는 요청에 결과만 새ID를 추가하면 연결검증을 완화하지 않고 재시도 가능하게 거절한다")
    void unknownPaymentBindingRemainsSafelyUnapplied() throws Exception {
        String n = prepare(false);
        jdbc.update(
                "UPDATE order_payment_relay SET remote_payment_id=NULL WHERE payment_group_id=?",
                id(n));
        jdbc.update(
                "UPDATE payment_group SET payment_id=NULL,status='PAYMENT_CONFIRMING',processing_expires_at=CURRENT_TIMESTAMP+INTERVAL '10 minutes' WHERE id=?",
                id(n));
        jdbc.update(
                "UPDATE orders SET status='PAYMENT_CONFIRMING' WHERE payment_group_id=?", id(n));
        tx.executeWithoutResult(
                t -> {
                    guard.lock(MEMBER_A);
                    refunds.compensateLocked(
                            groups.lockById(id(n)).orElseThrow(), "APPROVAL_UNKNOWN");
                });
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        ((ObjectNode) done.path("payload")).put("paymentId", PAYMENT);
        for (int i = 0; i < 2; i++)
            assertThatThrownBy(() -> apply(done)).isInstanceOf(BusinessException.class);
        assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
        assertThat(reserved(501)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_result_event", Long.class))
                .isZero();
        assertThat(intents.compensation(id(n)).status()).isEqualTo("REQUESTED");
        verifyNoInteractions(remote);
    }

    @Test
    @DisplayName("동시에 같은 완료 결과를 받아도 취소·재고·쿠폰은 한 번만 확정한다")
    void concurrentCompensationCompletionReleasesOnce() throws Exception {
        String n = prepare(true);
        relay.approvalUnknown(accept(n), Map.of("code", "UNKNOWN"));
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return apply(done);
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return apply(done);
                            });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_result_event", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("의미를 확인할 수 없는 HTTP500은 명시적UNKNOWN으로 추정하지 않고 기존기한까지 기다린다")
    void ambiguousHttpFailureDoesNotImmediatelyCompensate() {
        String n = prepare(false);
        var delivery = accept(n);
        when(remote.get(n))
                .thenReturn(
                        new Reply<>(
                                200,
                                true,
                                new Lookup(PAYMENT, "CREATED", 12000, 0, n + "-1", null),
                                null,
                                null));
        when(remote.approve(eq(n), any()))
                .thenReturn(new Reply<>(500, false, null, "INTERNAL", null));
        relayDispatcher.dispatch(delivery);
        assertThat(state(n)).isEqualTo("PAYMENT_CONFIRMING");
        assertThat(intents.fenced(id(n))).isFalse();
        assertThat(compensationRequests(n)).isZero();
    }

    @Test
    @DisplayName("보상 완료 저장 실패는 취소·자원해제·결과원장을 함께 롤백하고 같은결과 재수신으로 복구한다")
    void systemCompletionDatabaseFailureRollsBackResources() throws Exception {
        String n = prepare(true);
        relay.approvalUnknown(accept(n), Map.of("code", "UNKNOWN"));
        var done = result(requestId(n), "SUCCEEDED", 1, UUID.randomUUID().toString());
        jdbc.execute(
                "CREATE FUNCTION reject_compensation_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '완료저장 장애'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_compensation_completion BEFORE UPDATE ON core_refund_intent FOR EACH ROW EXECUTE FUNCTION reject_compensation_completion()");
        try {
            assertThatThrownBy(() -> apply(done)).isInstanceOf(RuntimeException.class);
            assertThat(state(n)).isEqualTo("COMPENSATION_PENDING");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT status FROM orders WHERE payment_group_id=?",
                                    String.class,
                                    id(n)))
                    .containsOnly("COMPENSATION_PENDING");
            assertThat(available(501)).isEqualTo(17);
            assertThat(reserved(501)).isEqualTo(3);
            assertThat(couponStatus()).isEqualTo("RESERVED");
            assertThat(intents.compensation(id(n)).status()).isEqualTo("REQUESTED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM core_refund_result_event", Long.class))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_compensation_completion ON core_refund_intent");
            jdbc.execute("DROP FUNCTION reject_compensation_completion()");
        }
        assertThat(apply(done)).isTrue();
        assertThat(state(n)).isEqualTo("CANCELLED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }
}
