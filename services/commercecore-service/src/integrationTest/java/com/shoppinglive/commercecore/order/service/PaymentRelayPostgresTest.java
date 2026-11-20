package com.shoppinglive.commercecore.order.service;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest;
import com.shoppinglive.commercecore.payment.relay.*;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(
        properties = {
            "commerce.commission-rate=0.1250",
            "commercecore.product.lookup.mode=local",
            "commercecore.payment-relay.recovery-enabled=false"
        })
@AutoConfigureMockMvc
class PaymentRelayPostgresTest extends OrderSheetPostgresSupport {
    static final String SELLER_B = "44444444-4444-4444-8444-444444444444";
    @Autowired PaymentRelayService relay;
    @Autowired PaymentRelayDispatcher relayDispatcher;
    @Autowired PaymentRelayStore relayStore;
    @Autowired ObjectMapper mapper;
    @MockitoBean PaymentRelayClient remote;
    @MockitoSpyBean OrderTimeSource time;
    @MockitoSpyBean EventOutbox outbox;
    Create remoteCreate;
    String remoteStatus;
    String remotePgOrderId;
    Instant approvedAt;
    final long paymentId = 7001;

    @BeforeEach
    void partnerContractDouble() {
        remoteCreate = null;
        remoteStatus = "CREATED";
        remotePgOrderId = null;
        approvedAt = null;
        jdbc.update("UPDATE product SET seller_id=? WHERE id=102", SELLER_B);
        when(remote.create(any()))
                .thenAnswer(
                        i -> {
                            Create body = i.getArgument(0);
                            if (remoteCreate == null) {
                                remoteCreate = body;
                                remotePgOrderId = body.orderRef() + "-1";
                            } else assertThat(body).isEqualTo(remoteCreate);
                            return new Reply<>(
                                    201,
                                    true,
                                    new Created(
                                            paymentId,
                                            remoteStatus,
                                            body.orderRef() + "-1",
                                            body.amount(),
                                            body.expiresAt()),
                                    null,
                                    null);
                        });
        when(remote.get(anyString()))
                .thenAnswer(
                        i ->
                                new Reply<>(
                                        200,
                                        true,
                                        new Lookup(
                                                paymentId,
                                                remoteStatus,
                                                remoteCreate.amount(),
                                                0,
                                                remotePgOrderId,
                                                approvedAt),
                                        null,
                                        null));
        doAnswer(
                        i -> {
                            remoteStatus = "APPROVING";
                            return new Reply<>(
                                    202,
                                    true,
                                    new ApprovalResult(paymentId, "PENDING", null, null),
                                    null,
                                    "/v1/internal/payments/" + remoteCreate.orderRef());
                        })
                .when(remote)
                .approve(anyString(), any());
    }

    PaymentRelayService.Creation createRelay(String key, boolean coupon) {
        return relay.create(
                MEMBER_A,
                key,
                new RelayOrderRequest(
                        List.of(
                                new CreateOrderSheetRequest.Item(501L, 2),
                                new CreateOrderSheetRequest.Item(502L, 1)),
                        null,
                        "구매자",
                        "01012345678",
                        coupon ? 9000L : 11000L,
                        coupon ? COUPON : null,
                        coupon ? 501L : null));
    }

    void prepare(String number) {
        relayDispatcher.dispatch(relay.prepare(MEMBER_A, number));
    }

    Approval request(String number) {
        return new Approval(MEMBER_A, "toss-key", remotePgOrderId, remoteCreate.amount());
    }

    com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore.Delivery accept(
            String number) {
        return relay.acceptApproval(MEMBER_A, number, "approval", request(number));
    }

    long id(String number) {
        return groups.findByGroupNumberAndMemberId(number, MEMBER_A).orElseThrow().getId();
    }

    void expiry(String number) {
        doReturn(groups.findById(id(number)).orElseThrow().getProcessingExpiresAt())
                .when(time)
                .now();
    }

    Approved fact(String number) {
        return new Approved(paymentId, number, remoteCreate.amount(), Instant.now());
    }

    long cartRow(String member, long sku, int quantity) {
        return jdbc.queryForObject(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) SELECT ?,product_id,id,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sku WHERE id=? RETURNING id",
                Long.class,
                member,
                quantity,
                sku);
    }

    String createCartRelay(long cart) {
        return relay.create(
                        MEMBER_A,
                        "cart-cleanup",
                        new RelayOrderRequest(
                                null,
                                List.of(
                                        new com.shoppinglive.commercecore.cart.dto.CartSelection(
                                                cart, 0L)),
                                "구매자",
                                "01012345678",
                                6000L,
                                COUPON,
                                501L))
                .number();
    }

    boolean cartExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE id=?", Integer.class, id)
                == 1;
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "quantity", "readded"})
    @DisplayName("최초 승인은 구매 SKU의 변경·재추가 행을 정리하고 다른 SKU·회원과 성공 후 재담기를 보존한다")
    void cleansPurchasedSkuOnce(String edit) {
        long selected = cartRow(MEMBER_A, 501, 2);
        long otherSku = cartRow(MEMBER_A, 503, 3);
        long otherProduct = cartRow(MEMBER_A, 502, 1);
        long otherMember = cartRow(MEMBER_B, 501, 4);
        String number = createCartRelay(selected);
        prepare(number);
        accept(number);
        long current = selected;
        if (edit.equals("quantity"))
            jdbc.update("UPDATE cart_item SET quantity=7,version=version+1 WHERE id=?", selected);
        if (edit.equals("readded")) {
            jdbc.update("DELETE FROM cart_item WHERE id=?", selected);
            current = cartRow(MEMBER_A, 501, 8);
        }
        var approved = fact(number);
        assertThat(relay.approved(approved)).isTrue();
        assertThat(cartExists(current)).isFalse();
        assertThat(cartExists(otherSku)).isTrue();
        assertThat(cartExists(otherProduct)).isTrue();
        assertThat(cartExists(otherMember)).isTrue();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT source_cart_item_id,source_cart_item_version FROM orders WHERE payment_group_id=?",
                                id(number)))
                .containsEntry("source_cart_item_id", selected)
                .containsEntry("source_cart_item_version", 0L);
        long readded = cartRow(MEMBER_A, 501, 5);
        assertThat(relay.approved(approved)).isFalse();
        assertThat(cartExists(readded)).isTrue();
        assertThat(reserved(501)).isZero();
        assertThat(available(501)).isEqualTo(18);
        assertThat(couponStatus()).isEqualTo("USED");
    }

    @Test
    @DisplayName("직접 구매 성공은 같은 회원의 동일 SKU 장바구니를 보존한다")
    void directPurchaseKeepsCart() {
        long cart = cartRow(MEMBER_A, 501, 2);
        String number = createRelay("direct", false).number();
        prepare(number);
        accept(number);
        assertThat(relay.approved(fact(number))).isTrue();
        assertThat(cartExists(cart)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"declined", "cancelled", "expired", "late"})
    @DisplayName("결제 실패·취소·만료와 만료 후 늦은 승인은 장바구니를 정리하지 않는다")
    void unsuccessfulOrdersKeepCart(String outcome) {
        long cart = cartRow(MEMBER_A, 501, 2);
        String number = createCartRelay(cart);
        if (outcome.equals("cancelled")) lifecycle.cancel(MEMBER_A, number);
        else if (outcome.equals("expired")) {
            makeExpired(sheets.get(MEMBER_A, number));
            assertThat(lifecycle.expire(id(number))).isTrue();
        } else {
            prepare(number);
            var delivery = accept(number);
            if (outcome.equals("declined")) relay.declined(delivery, Map.of("status", "DECLINED"));
            else {
                expiry(number);
                assertThat(relay.approved(fact(number))).isTrue();
            }
        }
        assertThat(cartExists(cart)).isTrue();
        if (outcome.equals("late")) {
            assertThat(relay.state(MEMBER_A, number).orderStatus())
                    .isEqualTo("COMPENSATION_PENDING");
            assertThat(reserved(501)).isEqualTo(2);
            assertThat(available(501)).isEqualTo(18);
            assertThat(couponStatus()).isEqualTo("RESERVED");
        } else {
            assertThat(reserved(501)).isZero();
            assertThat(available(501)).isEqualTo(20);
            assertThat(couponStatus()).isEqualTo("AVAILABLE");
        }
    }

    @Test
    @DisplayName("성공 알림 저장 실패는 Cart 삭제·주문·재고·쿠폰·승인 멱등표식을 롤백하고 재시도한다")
    void rollsBackCartCleanupAndRetriesApproval() {
        long cart = cartRow(MEMBER_A, 501, 2);
        String number = createCartRelay(cart);
        prepare(number);
        accept(number);
        EventOutbox target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("성공 알림 저장 실패"))
                .when(target)
                .append(
                        eq(
                                com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher
                                        .TOPIC),
                        any());
        var approved = fact(number);
        assertThatThrownBy(() -> relay.approved(approved))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cartExists(cart)).isTrue();
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PROCESSING");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM orders WHERE payment_group_id=?",
                                String.class,
                                id(number)))
                .containsOnly("PAYMENT_CONFIRMING");
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(relayStore.binding(id(number)).approvedAt()).isNull();
        doCallRealMethod()
                .when(target)
                .append(
                        eq(
                                com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher
                                        .TOPIC),
                        any());
        assertThat(relay.approved(approved)).isTrue();
        assertThat(cartExists(cart)).isFalse();
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("USED");
    }

    @Test
    @DisplayName("준비는 판매자별 할인후 금액과 설정 수수료를 보존하고 PENDING과 원래600초 기한을 유지한다")
    void preparationPreservesPendingAndImmutableSellerSnapshot() throws Exception {
        var created = createRelay("snapshot", true);
        String number = created.number();
        var before = relay.state(MEMBER_A, number);
        verifyNoInteractions(remote);
        prepare(number);
        var after = relay.state(MEMBER_A, number);
        assertThat(after.orderStatus()).isEqualTo("PENDING");
        assertThat(after.processingExpiresAt()).isNull();
        assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
        assertThat(after.paymentId()).isEqualTo(paymentId);
        assertThat(remoteCreate.sellerAmounts())
                .extracting(SellerAmount::sellerOrderNumber)
                .containsExactly(number + "-S1", number + "-S2");
        assertThat(remoteCreate.sellerAmounts())
                .extracting(SellerAmount::amount)
                .containsExactly(6000L, 3000L);
        assertThat(remoteCreate.sellerAmounts())
                .allSatisfy(s -> assertThat(s.commissionRate()).isEqualByComparingTo("0.1250"));
        jdbc.update("UPDATE sku SET price=99999 WHERE id=501");
        assertThat(createRelay("snapshot", true).number()).isEqualTo(number);
        assertThat(relayStore.creation(groups.findById(id(number)).orElseThrow()))
                .isEqualTo(remoteCreate);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(reserved(501)).isEqualTo(2);
        var createdPayload =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT payload FROM event_outbox WHERE event_type='PaymentGroupCreated'",
                                String.class));
        assertThat(createdPayload.path("payload").path("orders").findValuesAsText("amount"))
                .containsExactly("6000", "3000");
    }

    @Test
    @DisplayName("승인 접수만 별도600초와 HTTP 전달 원문을 함께 저장하며 재요청으로 연장하지 않는다")
    void approvalAcceptanceCommitsBeforeHttpAndDoesNotExtend() {
        String number = createRelay("accept", false).number();
        prepare(number);
        clearInvocations(remote);
        var originalExpiry = relay.state(MEMBER_A, number).expiresAt();
        var accepted = accept(number);
        var state = relay.state(MEMBER_A, number);
        verifyNoInteractions(remote);
        assertThat(state.orderStatus()).isEqualTo("PROCESSING");
        assertThat(state.processingExpiresAt()).isAfter(originalExpiry.minusSeconds(1));
        doReturn(state.processingExpiresAt().minusSeconds(1)).when(time).now();
        assertThat(accept(number).requestId()).isEqualTo(accepted.requestId());
        assertThat(relay.state(MEMBER_A, number).processingExpiresAt())
                .isEqualTo(state.processingExpiresAt());
        assertThatThrownBy(() -> relay.acceptApproval(MEMBER_A, number, "other", request(number)))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(deliveries.due(true)).noneMatch(d -> "APPROVAL".equals(d.kind()));
    }

    @Test
    @DisplayName("승인202와 UNKNOWN 조회는 실패로 처리하지 않고 같은 원문을 보존한다")
    void pendingAndUnknownNeverReleaseInventory() {
        String number = createRelay("unknown", true).number();
        prepare(number);
        var delivery = accept(number);
        relayDispatcher.dispatch(delivery);
        remoteStatus = "UNKNOWN";
        relayDispatcher.dispatch(deliveries.get(delivery.requestId()));
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("COMPENSATION_PENDING");
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        verify(remote, times(1)).approve(anyString(), any());
        assertThat(deliveries.get(delivery.requestId()).status()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("승인 응답 유실 뒤600초 전 정상 승인 사실은 보상 없이 PAID로 복구한다")
    void lostApprovalResponseRecoversByLookup() {
        String number = createRelay("lost", true).number();
        prepare(number);
        doAnswer(
                        i -> {
                            remoteStatus = "APPROVED";
                            approvedAt = Instant.now();
                            throw new ResourceAccessException("승인 응답 유실");
                        })
                .when(remote)
                .approve(anyString(), any());
        var delivery = accept(number);
        relayDispatcher.dispatch(delivery);
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PROCESSING");
        relayDispatcher.dispatch(deliveries.get(delivery.requestId()));
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PAID");
        assertThat(relay.state(MEMBER_A, number).compensation()).isNull();
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("USED");
        verify(remote, times(1)).approve(anyString(), any());
        assertThat(
                        relay.approved(
                                new Approved(paymentId, number, remoteCreate.amount(), approvedAt)))
                .isFalse();
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("HTTP 전 프로세스 종료에 해당하는 저장 원문은 새 전달기로 복구한다")
    void newDispatcherRecoversCommittedApproval() {
        String number = createRelay("restart", false).number();
        prepare(number);
        accept(number);
        doAnswer(
                        i -> {
                            remoteStatus = "APPROVED";
                            approvedAt = Instant.now();
                            return new Reply<>(
                                    200,
                                    true,
                                    new ApprovalResult(paymentId, "APPROVED", null, null),
                                    null,
                                    null);
                        })
                .when(remote)
                .approve(anyString(), any());
        new PaymentRelayDispatcher(remote, relay, deliveries, relayStore, lifecycle, true)
                .recover();
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("미전달 승인 원문도 처리기한에 도달하면 보상대기로 전환하고 새 승인을 보내지 않는다")
    void expiredUnsentApprovalNeverStartsPayment() {
        String number = createRelay("unsent", true).number();
        prepare(number);
        var delivery = accept(number);
        expiry(number);
        relayDispatcher.dispatch(delivery);
        verify(remote, never()).approve(anyString(), any());
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("COMPENSATION_PENDING");
        assertThat(available(501)).isEqualTo(18);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_relay_refund", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("만료 후 승인 중복은 주문을 복구하지 않고 환불 요청과 outbox를 한 번 저장한다")
    void lateApprovalRequestsRefundOnce() {
        String number = createRelay("late", true).number();
        prepare(number);
        accept(number);
        expiry(number);
        var fact = fact(number);
        assertThat(relay.approved(fact)).isTrue();
        assertThat(relay.approved(fact)).isFalse();
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("COMPENSATION_PENDING");
        assertThat(relay.state(MEMBER_A, number).refundStatus()).isEqualTo("REQUESTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refund_request_id FROM core_refund_intent", String.class))
                .matches("[0-9a-f-]{36}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundRequested' AND topic='commerce.refund.requests.v1'",
                                Long.class))
                .isEqualTo(1);
        assertThat(available(501)).isEqualTo(18);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("늦은 승인 outbox 저장 실패는 주문 만료·선점해제·환불 요청을 함께 롤백한다")
    void rollsBackLateApprovalWhenOutboxFails() {
        String number = createRelay("rollback", true).number();
        prepare(number);
        accept(number);
        expiry(number);
        EventOutbox target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("outbox 저장 실패"))
                .when(target)
                .append(eq("commerce.refund.requests.v1"), any());
        assertThatThrownBy(() -> relay.approved(fact(number)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(groups.findById(id(number)).orElseThrow().getStatus().name())
                .isEqualTo("PAYMENT_CONFIRMING");
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_relay_refund", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("승인과 처리기한이 경합하면 보상 요청 한 건과 예약보존에 수렴한다")
    void expiryAndApprovalSerializeAtBoundary() throws Exception {
        String number = createRelay("race", true).number();
        prepare(number);
        accept(number);
        expiry(number);
        var fact = fact(number);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return relay.approved(fact);
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return lifecycle.expire(id(number));
                            });
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isTrue();
            b.get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_refund_intent", Long.class))
                .isEqualTo(1);
        assertThat(available(501)).isEqualTo(18);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("준비된 PENDING 주문은 취소할 수 있고 Payment에 종료 요청이나 승인을 보내지 않는다")
    void preparedOrderCanCancelWithoutPaymentClosureContract() {
        String number = createRelay("cancel", true).number();
        prepare(number);
        lifecycle.cancel(MEMBER_A, number);
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> accept(number))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        verify(remote, never()).approve(anyString(), any());
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("확정 거절만 FAILED로 선점을 풀고 만료 뒤 실패는 추가 해제하지 않는다")
    void declineReleasesOnlyOnce() {
        String number = createRelay("declined", true).number();
        prepare(number);
        doReturn(
                        new Reply<>(
                                422,
                                false,
                                new ApprovalResult(null, "DECLINED", "REJECT_CARD_PAYMENT", "거절"),
                                "DECLINED",
                                null))
                .when(remote)
                .approve(anyString(), any());
        var delivery = accept(number);
        relayDispatcher.dispatch(delivery);
        relayDispatcher.dispatch(deliveries.get(delivery.requestId()));
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("늦은 승인 환불 성공 중복은 기록만 완료하며 만료 주문과 재고를 다시 바꾸지 않는다")
    void refundSuccessRecordsOnceWithoutChangingExpiredOrder() {
        String number = createRelay("refund", true).number();
        prepare(number);
        doReturn(groups.findById(id(number)).orElseThrow().getExpiresAt()).when(time).now();
        lifecycle.expire(id(number));
        jdbc.update(
                "INSERT INTO payment_relay_refund(refund_request_id,payment_group_id,remote_payment_id,amount,reason,status,approved_at,requested_at) VALUES('late-7001',?,7001,9000,'LATE_APPROVAL','REQUESTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(number));
        var items =
                remoteCreate.sellerAmounts().stream()
                        .map(s -> new RefundItem(s.sellerOrderNumber(), s.amount()))
                        .toList();
        var success =
                new RefundSucceeded(8001, "late-7001", "LATE_APPROVAL", items, 9000, Instant.now());
        assertThat(relay.refundSucceeded(number, success)).isTrue();
        assertThat(relay.refundSucceeded(number, success)).isFalse();
        assertThat(relay.state(MEMBER_A, number).refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThatThrownBy(
                        () ->
                                relay.refundFailed(
                                        number,
                                        new RefundFailed(
                                                8001, "late-7001", "LATE_APPROVAL", "REJECTED")))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
    }

    @Test
    @DisplayName("금액·수수료·원문 저장 중 실패하면 전체 주문과 선점이 롤백한다")
    void rejectedAmountRollsBackWholeOrder() {
        assertThatThrownBy(
                        () ->
                                relay.create(
                                        MEMBER_A,
                                        "bad",
                                        new RelayOrderRequest(
                                                List.of(new CreateOrderSheetRequest.Item(501L, 2)),
                                                null,
                                                "구매자",
                                                "01012345678",
                                                1L,
                                                null,
                                                null)))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Long.class)).isZero();
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("공개 주문서와 승인 API는 본인 JWT를 쓰고 다른 회원 조회를 거절한다")
    void publicApiUsesAuthenticatedBuyerAndPublishesProcessingDeadline() throws Exception {
        var body =
                new RelayOrderRequest(
                        List.of(new CreateOrderSheetRequest.Item(501L, 2)),
                        null,
                        "구매자",
                        "01012345678",
                        8000L,
                        null,
                        null);
        var response =
                mvc.perform(
                                post("/v1/order-sheets")
                                        .header("Authorization", bearer(MEMBER_A))
                                        .header("X-Idempotency-Key", "api")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.payment.orderStatus").value("PENDING"))
                        .andReturn();
        String number =
                mapper.readTree(response.getResponse().getContentAsString())
                        .path("order")
                        .path("orderNumber")
                        .asText();
        mvc.perform(
                        get("/v1/order-sheets/{number}", number)
                                .header("Authorization", bearer(MEMBER_B)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/v1/order-sheets/{number}/payment/approval", number)
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "api-approval")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"paymentKey\":\"key\",\"pgOrderId\":\""
                                                + number
                                                + "-1\",\"amount\":8000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.processingExpiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("다른 판매자의 쿠폰을 적용하면 전체 주문과 선점을 롤백한다")
    void rejectsCouponFromAnotherSeller() {
        assertThatThrownBy(
                        () ->
                                relay.create(
                                        MEMBER_A,
                                        "wrong-seller",
                                        new RelayOrderRequest(
                                                List.of(new CreateOrderSheetRequest.Item(502L, 1)),
                                                null,
                                                "구매자",
                                                "01012345678",
                                                1000L,
                                                COUPON,
                                                502L)))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Long.class)).isZero();
        assertThat(reserved(502)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("환불 금액 불일치는 기록하지 않고 확정 실패 중복은 한 번만 반영한다")
    void validatesRefundResultAndRecordsFailure() {
        String number = createRelay("refund-failed", true).number();
        prepare(number);
        doReturn(groups.findById(id(number)).orElseThrow().getExpiresAt()).when(time).now();
        lifecycle.expire(id(number));
        jdbc.update(
                "INSERT INTO payment_relay_refund(refund_request_id,payment_group_id,remote_payment_id,amount,reason,status,approved_at,requested_at) VALUES('late-7001',?,7001,9000,'LATE_APPROVAL','REQUESTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(number));
        assertThatThrownBy(
                        () ->
                                relay.refundSucceeded(
                                        number,
                                        new RefundSucceeded(
                                                8001,
                                                "late-7001",
                                                "LATE_APPROVAL",
                                                List.of(),
                                                1,
                                                Instant.now())))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(relay.state(MEMBER_A, number).refundStatus()).isEqualTo("REQUESTED");
        var failure = new RefundFailed(8001, "late-7001", "LATE_APPROVAL", "REJECTED");
        assertThat(relay.refundFailed(number, failure)).isTrue();
        assertThat(relay.refundFailed(number, failure)).isFalse();
        assertThat(relay.state(MEMBER_A, number).refundStatus()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("준비 응답 유실은 같은 생성 원문을 다시 보내고 PENDING 기한을 보존한다")
    void lostPreparationResponseReusesOriginal() {
        String number = createRelay("prepare-lost", false).number();
        var before = relay.state(MEMBER_A, number);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        i -> {
                            Create body = i.getArgument(0);
                            if (calls.getAndIncrement() == 0) {
                                remoteCreate = body;
                                remotePgOrderId = body.orderRef() + "-1";
                                throw new ResourceAccessException("생성 응답 유실");
                            }
                            assertThat(body).isEqualTo(remoteCreate);
                            return new Reply<>(
                                    200,
                                    true,
                                    new Created(
                                            paymentId,
                                            "CREATED",
                                            remotePgOrderId,
                                            body.amount(),
                                            body.expiresAt()),
                                    null,
                                    null);
                        })
                .when(remote)
                .create(any());
        prepare(number);
        assertThat(relay.state(MEMBER_A, number).paymentId()).isNull();
        var original = deliveries.relay(id(number), "PREPARE").requestId();
        prepare(number);
        assertThat(deliveries.relay(id(number), "PREPARE").requestId()).isEqualTo(original);
        assertThat(relay.state(MEMBER_A, number).expiresAt()).isEqualTo(before.expiresAt());
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PENDING");
        assertThat(relay.state(MEMBER_A, number).paymentId()).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("새 결제창을 받으면 이전 pgOrderId 승인은 거절하고 현재 결제창만 접수한다")
    void newCheckoutRejectsStaleApproval() {
        String number = createRelay("checkout", false).number();
        prepare(number);
        var old = request(number);
        doAnswer(
                        i -> {
                            remotePgOrderId = number + "-2";
                            return new Reply<>(
                                    201,
                                    true,
                                    new Checkout(
                                            remotePgOrderId,
                                            remoteCreate.amount(),
                                            remoteCreate.expiresAt()),
                                    null,
                                    null);
                        })
                .when(remote)
                .checkout(number);
        var state = relayDispatcher.checkout(MEMBER_A, number);
        assertThat(state.pgOrderId()).isEqualTo(number + "-2");
        assertThatThrownBy(() -> relay.acceptApproval(MEMBER_A, number, "stale", old))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PENDING");
        accept(number);
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("조회 대기 중 처리기한이 지나면 새 승인 HTTP를 보내지 않고 보상대기로 전환한다")
    void expiresWhileLookupWaits() {
        String number = createRelay("lookup-deadline", false).number();
        prepare(number);
        var delivery = accept(number);
        doAnswer(
                        i -> {
                            expiry(number);
                            return new Reply<>(
                                    200,
                                    true,
                                    new Lookup(
                                            paymentId,
                                            "CREATED",
                                            remoteCreate.amount(),
                                            0,
                                            remotePgOrderId,
                                            null),
                                    null,
                                    null);
                        })
                .when(remote)
                .get(number);
        relayDispatcher.dispatch(delivery);
        verify(remote, never()).approve(anyString(), any());
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("COMPENSATION_PENDING");
        assertThat(available(501)).isEqualTo(18);
    }

    @Test
    @DisplayName("수수료 설정이 없으면 신규 중계 주문·재고선점·전달원문을 모두 롤백한다")
    void missingCommissionRateRollsBackCreation() {
        var target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(relay);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "commissionRate", "");
        try {
            assertThatThrownBy(() -> createRelay("missing-rate", false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("commerce.commission-rate");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_group", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_order", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM payment_delivery_request", Integer.class))
                    .isZero();
            assertThat(available(501)).isEqualTo(20);
            assertThat(reserved(501)).isZero();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    target, "commissionRate", "0.1250");
        }
    }

    @Autowired PaymentGroupExpirationScheduler expiryScheduler;

    @Test
    @DisplayName("준비된 PENDING 주문도 추가 사용자 요청 없이 만료 배치가 선점과 쿠폰을 한 번 해제한다")
    void preparedPendingExpiresByScheduler() {
        String number = createRelay("prepared-scheduler", true).number();
        prepare(number);
        jdbc.update(
                "UPDATE payment_group SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE group_number=?",
                number);
        expiryScheduler.run();
        expiryScheduler.run();
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        verify(remote, never()).get(anyString());
        verify(remote, never()).approve(anyString(), any());
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    OrderSheetService transactionalSheets;

    @Test
    @DisplayName("연결 풀 크기만큼 동시에 접수해도 내부 guard 준비에 추가 연결을 기다리지 않고 원자적으로 생성한다")
    void concurrentCreationDoesNotRequireNestedGuardConnection() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(3);
        doAnswer(
                        i -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            barrier.await(15, java.util.concurrent.TimeUnit.SECONDS);
                            return i.callRealMethod();
                        })
                .when(transactionalSheets)
                .createForPayment(anyString(), anyString(), any(), any());
        var members = List.of(MEMBER_A, MEMBER_B, "55555555-5555-4555-8555-555555555555");
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var requests =
                    members.stream()
                            .map(
                                    member ->
                                            workers.submit(
                                                    () ->
                                                            relay.create(
                                                                    member,
                                                                    "concurrent",
                                                                    new RelayOrderRequest(
                                                                            List.of(
                                                                                    new CreateOrderSheetRequest
                                                                                            .Item(
                                                                                            501L,
                                                                                            1)),
                                                                            null,
                                                                            "구매자",
                                                                            "01012345678",
                                                                            4000L,
                                                                            null,
                                                                            null))))
                            .toList();
            for (var request : requests)
                assertThat(request.get(20, java.util.concurrent.TimeUnit.SECONDS).created())
                        .isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_group", Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_delivery_request WHERE kind='PREPARE'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(available(501)).isEqualTo(17);
        assertThat(reserved(501)).isEqualTo(3);
        verify(remote, never()).create(any());
    }

    @Test
    @DisplayName("늦은 생성 응답이 돌아와도 이미 저장한 새 결제창을 원래 결제창으로 되돌리지 않는다")
    void latePreparationDoesNotRestoreStaleCheckout() {
        String number = createRelay("late-prepare", false).number();
        prepare(number);
        var delivery = deliveries.relay(id(number), "PREPARE");
        var original =
                new Created(
                        paymentId,
                        "CREATED",
                        remotePgOrderId,
                        remoteCreate.amount(),
                        remoteCreate.expiresAt());
        relay.checkout(
                MEMBER_A,
                number,
                new Checkout(number + "-2", remoteCreate.amount(), remoteCreate.expiresAt()));
        relay.prepared(delivery, original);
        assertThat(relay.state(MEMBER_A, number).pgOrderId()).isEqualTo(number + "-2");
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("늦은202는 확정 실패 표시를 되돌리지 않고 이후 진짜 늦은 승인만 환불 요청으로 기록한다")
    void delayedPendingDoesNotRegressConfirmedDecline() {
        String number = createRelay("late-pending", false).number();
        prepare(number);
        var delivery = accept(number);
        relay.declined(delivery, java.util.Map.of("status", "DECLINED"));
        relay.pending(delivery, java.util.Map.of("status", "PENDING"), "PENDING");
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("FAILED");
        assertThat(relay.state(MEMBER_A, number).paymentStatus()).isEqualTo("DECLINED");
        assertThat(deliveries.relay(id(number), "APPROVAL").status()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        relay.approved(fact(number));
        relay.pending(delivery, java.util.Map.of("status", "PENDING"), "PENDING");
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("FAILED");
        assertThat(relay.state(MEMBER_A, number).paymentStatus()).isEqualTo("APPROVED");
        assertThat(relay.state(MEMBER_A, number).refundStatus()).isEqualTo("REQUESTED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("새 활성 주문은 JWT 본인만 복구하고 재조회해도 기한·전달 원문을 바꾸지 않는다")
    void recoversOwnedActiveOrderWithoutRestartingPayment() throws Exception {
        mvc.perform(get("/v1/order-sheets/active")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isNoContent());
        String number = createRelay("active", false).number();
        prepare(number);
        var before = relay.state(MEMBER_A, number);
        var delivery = accept(number);
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("order.orderNumber").value(number))
                .andExpect(jsonPath("payment.orderStatus").value("PROCESSING"));
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_B)))
                .andExpect(status().isNoContent());
        assertThat(relay.state(MEMBER_A, number).expiresAt()).isEqualTo(before.expiresAt());
        assertThat(deliveries.relay(id(number), "APPROVAL").requestId())
                .isEqualTo(delivery.requestId());
        verify(remote, never()).get(anyString());
        verify(remote, never()).approve(anyString(), any());
    }

    @Test
    @DisplayName("활성 조회의 처리기한 경계는 보상대기 주문과 예약을 유지한다")
    void activeReadExpiresAtBoundaryOnce() throws Exception {
        String number = createRelay("active-expiry", true).number();
        prepare(number);
        accept(number);
        expiry(number);
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("order.status").value("COMPENSATION_PENDING"));
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("order.status").value("COMPENSATION_PENDING"));
        assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("COMPENSATION_PENDING");
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @ParameterizedTest(name = "정책 {0}")
    @ValueSource(ints = {1, 2})
    @DisplayName("이전 미연결 진행중 내부 주문은 활성 없음으로 숨기지 않고 재정합 필요409를 반환한다")
    void activeReadReportsUnlinkedHistoricalOrder(int policy) throws Exception {
        var old = create(1);
        jdbc.update("UPDATE payment_group SET policy_version=? WHERE id=?", policy, groupId(old));
        mvc.perform(get("/v1/order-sheets/active").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_delivery_request", Integer.class))
                .isZero();
        assertThat(groups.findById(groupId(old)).orElseThrow().getStatus().name())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(501)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT policy_version FROM payment_group WHERE id=?",
                                Integer.class,
                                groupId(old)))
                .isEqualTo(policy);
    }

    @Test
    @DisplayName("구매자 단일 상품 견적은 실제 SKU를 명시하며 새 주문서가 가격·판매를 재검증한다")
    void resolvesActualSingleSkuWithoutIdentityGuess() throws Exception {
        mvc.perform(
                        get("/v1/orders/checkout")
                                .param("productId", "102")
                                .param("quantity", "2")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("productId").value(102))
                .andExpect(jsonPath("skuId").value(502))
                .andExpect(jsonPath("salesId").value(502))
                .andExpect(jsonPath("payableAmount").value(6000));
        jdbc.update("UPDATE sku SET price=3500 WHERE id=502");
        assertThatThrownBy(
                        () ->
                                relay.create(
                                        MEMBER_A,
                                        "stale-quote",
                                        new RelayOrderRequest(
                                                List.of(new CreateOrderSheetRequest.Item(502L, 2)),
                                                null,
                                                "구매자",
                                                "01012345678",
                                                6000L,
                                                null,
                                                null)))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(reserved(502)).isZero();
        jdbc.update("UPDATE product SET sale_enabled=FALSE WHERE id=102");
        mvc.perform(
                        get("/v1/orders/checkout")
                                .param("productId", "102")
                                .param("quantity", "2")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("skuId").value(502))
                .andExpect(jsonPath("orderable").value(false));
        assertThatThrownBy(
                        () ->
                                relay.create(
                                        MEMBER_A,
                                        "stopped-quote",
                                        new RelayOrderRequest(
                                                List.of(new CreateOrderSheetRequest.Item(502L, 2)),
                                                null,
                                                "구매자",
                                                "01012345678",
                                                7000L,
                                                null,
                                                null)))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(reserved(502)).isZero();
    }

    @Test
    @DisplayName("구매자 SKU 식별은 복수 SKU와 삭제 상품을 기존 정책대로 거절하고 선점하지 않는다")
    void singleSkuLookupKeepsMultipleAndDeletedProductRules() throws Exception {
        mvc.perform(
                        get("/v1/orders/checkout")
                                .param("productId", "101")
                                .param("quantity", "1")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isConflict());
        jdbc.update(
                "UPDATE product SET sale_enabled=FALSE, deleted_at=CURRENT_TIMESTAMP WHERE id=102");
        mvc.perform(
                        get("/v1/orders/checkout")
                                .param("productId", "102")
                                .param("quantity", "1")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isNotFound());
        assertThat(reserved(501)).isZero();
        assertThat(reserved(502)).isZero();
    }
}
