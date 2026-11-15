package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 승인 API의 결과 불명·보상 취소 응답을 실제 PostgreSQL에서 HTTP로 확인한다. 응답 대기는 300ms로 줄인다. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.approval.response-wait=300ms")
class ApprovalApiPostgresTest extends ApprovalPostgresTestSupport {
    static final String TOKEN = "payment-approval-postgres-test-credential-32";

    @Autowired MockMvc mvc;

    final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseToss() {
        release.countDown();
    }

    ResultActions approve(ApproveCommand c) throws Exception {
        return mvc.perform(
                post("/v1/internal/payments/{orderRef}/approval", c.orderRef())
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"buyerId":"%s","paymentKey":"%s","pgOrderId":"%s","amount":%d}
                                """
                                        .formatted(
                                                c.buyerId(),
                                                c.paymentKey(),
                                                c.pgOrderId(),
                                                c.amount())));
    }

    void expectPending(ResultActions result, ApproveCommand c) throws Exception {
        result.andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/internal/payments/" + c.orderRef()))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId(c.orderRef())))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    void awaitStatus(String orderRef, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!expected.equals(status(orderRef)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(status(orderRef)).isEqualTo(expected);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-10] 두 번 모두 결과를 모르면 202 PENDING이고 UNKNOWN(첫 조회 10초, 마감 30분)으로 남는다")
    void unknownTwiceAnswersPending() throws Exception {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Unknown("읽기 타임아웃"));

        expectPending(approve(c), c);
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT next_check_at > now() + INTERVAL '5' SECOND"
                                        + " AND check_deadline_at > now() + INTERVAL '29' MINUTE"
                                        + " FROM payment WHERE order_ref = ?",
                                Boolean.class,
                                c.orderRef()))
                .isTrue();
        verify(toss, times(2)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-07] 토스 응답 값이 다르면 보상 취소 뒤 409 PAYMENT_CLOSED이고 CANCELED이며 이벤트가 없다")
    void mismatchAnswersClosed() throws Exception {
        ApproveCommand c = newPayment();
        TossPayment wrongAmount =
                new TossPayment(
                        c.paymentKey(), c.pgOrderId(), "DONE", 1, done(c).approvedAt(), true);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(wrongAmount));
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenReturn(new TossResult.Success(wrongAmount));

        approve(c)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"));
        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(outboxRows(c.orderRef())).isEmpty();
        verify(toss).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-11][PAY-CONFIRM-10] 대기 시간을 넘긴 뒤에도 결과를 모르면 202를 준 결제가 UNKNOWN으로 바뀐다")
    void lateUnknownBecomesUnknown() throws Exception {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            release.await(10, TimeUnit.SECONDS);
                            return new TossResult.Unknown("읽기 타임아웃");
                        });

        expectPending(approve(c), c);
        assertThat(status(c.orderRef())).isEqualTo("APPROVING");

        release.countDown();
        awaitStatus(c.orderRef(), "UNKNOWN");
    }

    @Test
    @DisplayName("[PAY-CONFIRM-11][PAY-CONFIRM-06] 대기 시간을 넘긴 승인도 뒤에서 APPROVED·분개·이벤트를 1번 남긴다")
    void lateSuccessApproves() throws Exception {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            release.await(10, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });

        expectPending(approve(c), c);
        release.countDown();
        awaitStatus(c.orderRef(), "APPROVED");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        approve(c).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
    }
}
