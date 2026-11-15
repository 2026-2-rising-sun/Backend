package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.zaxxer.hikari.HikariDataSource;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** 승인 API를 HTTP로 부른다(H2). 응답 대기는 테스트에서만 300ms로 줄인다. 토스는 테스트 대역이다. */
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:payment_approval_api;MODE=PostgreSQL",
            "payment.approval.response-wait=300ms"
        })
class ApprovalApiTest extends PaymentApiTestSupport {
    @MockitoBean TossPaymentsClient toss;
    @Autowired DataSource dataSource;

    /** 토스 대역을 멈춰 둔 테스트가 끝나면 풀어 준다(뒤 작업이 다음 테스트에 남지 않게). */
    final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseToss() {
        release.countDown();
    }

    String created() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        return orderRef;
    }

    static String approval(String buyerId, String paymentKey, String pgOrderId, long amount) {
        return """
                {"buyerId":"%s","paymentKey":"%s","pgOrderId":"%s","amount":%d}
                """
                .formatted(buyerId, paymentKey, pgOrderId, amount);
    }

    ResultActions approve(String orderRef, String json) throws Exception {
        return mvc.perform(
                post("/v1/internal/payments/{orderRef}/approval", orderRef)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    ResultActions approve(String orderRef) throws Exception {
        return approve(orderRef, approval(BUYER, "tgen_" + orderRef, orderRef + "-1", 30000));
    }

    static TossPayment done(String orderRef) {
        return new TossPayment(
                "tgen_" + orderRef,
                orderRef + "-1",
                "DONE",
                30000,
                OffsetDateTime.parse("2026-11-10T14:02:11+09:00"),
                true);
    }

    String status(String orderRef) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE order_ref = ?", String.class, orderRef);
    }

    long paymentId(String orderRef) {
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
    }

    int outboxRows(String orderRef) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE message_key = ?", Integer.class, orderRef);
    }

    /** 뒤에서 도는 반영이 끝날 때까지 기다린다. */
    void awaitStatus(String orderRef, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!expected.equals(status(orderRef)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(status(orderRef)).isEqualTo(expected);
    }

    ResultActions expectPending(ResultActions result, String orderRef) throws Exception {
        return result.andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/internal/payments/" + orderRef))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId(orderRef)))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    void expectError(ResultActions result, int httpStatus, String code) throws Exception {
        result.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    @Test
    @DisplayName("[PAY-CONFIRM-01] 결제 없음 404, orderRef 불일치 400, 구매자 불일치 403, 금액 불일치 400을 HTTP로 답하고 토스를 부르지 않는다")
    void rejectsMismatches() throws Exception {
        String orderRef = created();
        String key = "tgen_" + orderRef;
        String other = created();

        expectError(approve(newOrderRef()), 404, "PAYMENT_NOT_FOUND");
        expectError(
                approve(orderRef, approval(BUYER, key, other + "-1", 30000)),
                400,
                "ORDER_ID_MISMATCH");
        expectError(
                approve(orderRef, approval(OTHER_BUYER, key, orderRef + "-1", 29999)),
                403,
                "BUYER_MISMATCH");
        expectError(
                approve(orderRef, approval(BUYER, key, orderRef + "-1", 29999)),
                400,
                "AMOUNT_MISMATCH");

        assertThat(status(orderRef)).isEqualTo("CREATED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("승인 요청 형식이 틀리면 400 INVALID_REQUEST이고 토스를 부르지 않는다")
    void rejectsInvalidBody() throws Exception {
        String orderRef = created();
        String ok = orderRef + "-1";
        for (String json :
                new String[] {
                    "{}",
                    approval("not-a-uuid", "tgen_x", ok, 30000),
                    approval(BUYER, "", ok, 30000),
                    approval(BUYER, "k".repeat(201), ok, 30000),
                    approval(BUYER, "tgen_x", "", 30000),
                    approval(BUYER, "tgen_x", "O".repeat(65), 30000),
                    approval(BUYER, "tgen_x", ok, 0)
                }) {
            expectError(approve(orderRef, json), 400, "INVALID_REQUEST");
        }
        assertThat(status(orderRef)).isEqualTo("CREATED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-02] 예전 결제창의 pgOrderId면 409 STALE_CHECKOUT이고 토스를 부르지 않는다")
    void rejectsStaleCheckout() throws Exception {
        String orderRef = created();
        checkout(orderRef).andExpect(status().isCreated());

        expectError(approve(orderRef), 409, "STALE_CHECKOUT");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-03] 만료된 결제는 409 PAYMENT_CLOSED이고 토스를 부르지 않는다")
    void rejectsExpired() throws Exception {
        String orderRef = created();
        expire(orderRef);

        expectError(approve(orderRef), 409, "PAYMENT_CLOSED");
        assertThat(status(orderRef)).isEqualTo("CREATED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-05] 이미 승인됐으면 200, 처리 중·결과 불명이면 202 PENDING(Location), 닫혔으면 409이고 토스를 부르지 않는다")
    void answersByStatus() throws Exception {
        String approved = created();
        setStatus(approved, "APPROVED");
        expire(approved);
        approve(approved)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId(approved)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        for (String processing : new String[] {"APPROVING", "UNKNOWN"}) {
            String orderRef = created();
            setStatus(orderRef, processing);
            expectPending(approve(orderRef), orderRef);
        }
        String canceled = created();
        setStatus(canceled, "CANCELED");
        expectError(approve(canceled), 409, "PAYMENT_CLOSED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-06] 토스가 승인하면 200 {paymentId, status: APPROVED}이고 APPROVED·이벤트가 남는다")
    void approved() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(done(orderRef)));

        approve(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId(orderRef)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.error").doesNotExist());
        assertThat(status(orderRef)).isEqualTo("APPROVED");
        assertThat(outboxRows(orderRef)).isEqualTo(1);

        // 새로고침(같은 요청 반복)도 200이고 토스를 다시 부르지 않는다
        approve(orderRef).andExpect(status().isOk());
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-08] 토스가 거절하면 422 DECLINED이고 data에 {status: DECLINED, code, message}를 담으며 CREATED로 돌아간다")
    void declined() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Declined("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족"));

        approve(orderRef)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.code").value("REJECT_CARD_PAYMENT"))
                .andExpect(jsonPath("$.data.message").value("한도초과 혹은 잔액부족"))
                .andExpect(jsonPath("$.error.code").value("DECLINED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
        assertThat(status(orderRef)).isEqualTo("CREATED");

        // 같은 결제창으로 다시 승인하면 STALE_CHECKOUT, 새 결제창이면 다시 승인할 수 있다
        expectError(approve(orderRef), 409, "STALE_CHECKOUT");
        checkout(orderRef).andExpect(status().isCreated());
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(
                        new TossResult.Success(
                                new TossPayment(
                                        "tgen_" + orderRef,
                                        orderRef + "-2",
                                        "DONE",
                                        30000,
                                        OffsetDateTime.parse("2026-11-10T14:02:11+09:00"),
                                        true)));
        approve(orderRef, approval(BUYER, "tgen_" + orderRef, orderRef + "-2", 30000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("[PAY-CONFIRM-09] 요청이 토스에 가지 않았으면 503 PG_UNAVAILABLE이고 CREATED로 돌아간다")
    void pgUnavailable() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.NotSent("연결 거부"));

        expectError(approve(orderRef), 503, "PG_UNAVAILABLE");
        assertThat(status(orderRef)).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("[PAY-CONFIRM-11][PAY-CONFIRM-06] 토스 응답이 대기 시간 안에 없으면 202 PENDING을 먼저 주고, 뒤에서 승인을 반영해 APPROVED·이벤트가 남는다")
    void lateSuccessAnswersPendingThenApproves() throws Exception {
        String orderRef = created();
        AtomicInteger activeConnections = new AtomicInteger(-1);
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            // 토스를 기다리는 동안 요청 스레드도 토스 호출도 DB 연결을 잡지 않는다
                            activeConnections.set(
                                    hikari.getHikariPoolMXBean().getActiveConnections());
                            release.await(10, TimeUnit.SECONDS);
                            return new TossResult.Success(done(orderRef));
                        });

        long started = System.nanoTime();
        expectPending(approve(orderRef), orderRef);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).as("응답 대기 300ms 근처에서 답한다").isBetween(250L, 3000L);
        assertThat(status(orderRef)).isEqualTo("APPROVING");
        assertThat(activeConnections.get()).isZero();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();

        release.countDown();
        awaitStatus(orderRef, "APPROVED");
        assertThat(outboxRows(orderRef)).isEqualTo(1);
        approve(orderRef).andExpect(status().isOk());
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-11][PAY-CONFIRM-08] 대기 시간을 넘긴 뒤 토스가 거절하면 202를 준 결제가 CREATED로 돌아간다")
    void lateDeclineRevertsToCreated() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            release.await(10, TimeUnit.SECONDS);
                            return new TossResult.Declined("REJECT_CARD_COMPANY", "카드사 거절");
                        });

        expectPending(approve(orderRef), orderRef);
        assertThat(status(orderRef)).isEqualTo("APPROVING");

        release.countDown();
        awaitStatus(orderRef, "CREATED");
        assertThat(outboxRows(orderRef)).isZero();
    }

    @Test
    @DisplayName("[PAY-CONFIRM-05][PAY-CONFIRM-11] 첫 요청이 토스를 기다리는 동안 온 같은 승인 요청은 바로 202이고 토스는 1번만 불리며, 반영 뒤 재요청은 200이다")
    void repeatedRequestWhileWaitingCallsTossOnce() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            release.await(10, TimeUnit.SECONDS);
                            return new TossResult.Success(done(orderRef));
                        });

        expectPending(approve(orderRef), orderRef);
        expectPending(approve(orderRef), orderRef);

        release.countDown();
        awaitStatus(orderRef, "APPROVED");
        approve(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-11] 결과 반영 중 예외가 나도 요청은 202 PENDING으로 답하고 결제는 처리 중으로 남는다")
    void processingFailureAnswersPending() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("테스트: 예상하지 못한 오류"));

        expectPending(approve(orderRef), orderRef);
        assertThat(status(orderRef)).isEqualTo("APPROVING");
    }
}
