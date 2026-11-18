package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.config.PaymentError;
import com.shoppinglive.payment.config.PaymentException;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 승인 ① 검증. 검사에 걸리면 상태를 바꾸지 않고 토스를 부르지 않는다(H2). */
class ApprovalValidationTest extends PaymentApiTestSupport {
    @Autowired ApprovalService approvals;
    @MockitoBean TossPaymentsClient toss;

    String created() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef);
        return orderRef;
    }

    static ApproveCommand command(String orderRef) {
        return new ApproveCommand(orderRef, BUYER, "tgen_" + orderRef, orderRef + "-1", 30000);
    }

    PaymentError rejected(ApproveCommand command) {
        Throwable thrown = null;
        try {
            approvals.approve(command);
        } catch (PaymentException e) {
            thrown = e;
        }
        assertThat(thrown).as("검증 실패 예외").isInstanceOf(PaymentException.class);
        return ((PaymentException) thrown).error();
    }

    String status(String orderRef) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE order_ref = ?", String.class, orderRef);
    }

    int pgCalls(String orderRef) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_call c JOIN payment p ON p.id = c.payment_id WHERE p.order_ref = ?",
                Integer.class,
                orderRef);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-01] 결제 없음·orderRef 불일치·구매자 불일치·금액 불일치를 이 순서로 거절하고 토스를 부르지 않는다")
    void rejectsMismatchesWithoutCallingToss() throws Exception {
        String orderRef = created();
        ApproveCommand ok = command(orderRef);

        assertThat(rejected(command(newOrderRef()))).isEqualTo(PaymentError.PAYMENT_NOT_FOUND);
        String otherRef = created();
        assertThat(
                        rejected(
                                new ApproveCommand(
                                        orderRef, BUYER, ok.paymentKey(), otherRef + "-1", 30000)))
                .isEqualTo(PaymentError.ORDER_ID_MISMATCH);
        assertThat(
                        rejected(
                                new ApproveCommand(
                                        orderRef, BUYER, ok.paymentKey(), "NOHYPHEN", 30000)))
                .isEqualTo(PaymentError.ORDER_ID_MISMATCH);
        // 구매자와 금액이 모두 다르면 먼저 검사하는 구매자로 거절한다
        assertThat(
                        rejected(
                                new ApproveCommand(
                                        orderRef,
                                        OTHER_BUYER,
                                        ok.paymentKey(),
                                        ok.pgOrderId(),
                                        29999)))
                .isEqualTo(PaymentError.BUYER_MISMATCH);
        assertThat(
                        rejected(
                                new ApproveCommand(
                                        orderRef, BUYER, ok.paymentKey(), ok.pgOrderId(), 29999)))
                .isEqualTo(PaymentError.AMOUNT_MISMATCH);

        assertThat(status(orderRef)).isEqualTo("CREATED");
        assertThat(pgCalls(orderRef)).isZero();
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-02] 예전 결제창의 pgOrderId나 비워진 pg_order_id면 409 STALE_CHECKOUT이고 토스를 부르지 않는다")
    void rejectsStaleCheckout() throws Exception {
        String orderRef = created();
        checkout(orderRef); // 결제창 2회차: 현재 pg_order_id = {orderRef}-2

        assertThat(rejected(command(orderRef))).isEqualTo(PaymentError.STALE_CHECKOUT);

        jdbc.update("UPDATE payment SET pg_order_id = NULL WHERE order_ref = ?", orderRef);
        assertThat(rejected(command(orderRef))).isEqualTo(PaymentError.STALE_CHECKOUT);

        assertThat(status(orderRef)).isEqualTo("CREATED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-03] 만료된 CREATED 결제는 409 PAYMENT_CLOSED이고 토스를 부르지 않는다")
    void rejectsExpired() throws Exception {
        String orderRef = created();
        expire(orderRef);

        assertThat(rejected(command(orderRef))).isEqualTo(PaymentError.PAYMENT_CLOSED);
        assertThat(status(orderRef)).isEqualTo("CREATED");
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-05] 이미 승인된 결제는 만료가 지나도 승인됨, 처리 중이면 처리 중, 닫혔으면 PAYMENT_CLOSED로 답하고 토스를 부르지 않는다")
    void answersByStatusWithoutCallingToss() throws Exception {
        String approved = created();
        setStatus(approved, "APPROVED");
        expire(approved);
        assertThat(approvals.approve(command(approved)))
                .isInstanceOf(ApprovalResult.Approved.class);

        for (String processing : new String[] {"APPROVING", "UNKNOWN"}) {
            String orderRef = created();
            setStatus(orderRef, processing);
            assertThat(approvals.approve(command(orderRef)))
                    .as(processing)
                    .isInstanceOf(ApprovalResult.Pending.class);
        }
        for (String closed : new String[] {"CANCELED", "PARTIALLY_REFUNDED", "REFUNDED"}) {
            String orderRef = created();
            setStatus(orderRef, closed);
            assertThat(rejected(command(orderRef)))
                    .as(closed)
                    .isEqualTo(PaymentError.PAYMENT_CLOSED);
        }
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-04] 다른 결제가 이미 쓴 paymentKey면 400 INVALID_REQUEST이고 상태를 바꾸지 않으며 토스를 부르지 않는다")
    void rejectsPaymentKeyUsedByAnotherPayment() throws Exception {
        String first = created();
        jdbc.update(
                "UPDATE payment SET pg_payment_key = ? WHERE order_ref = ?", "tgen_shared", first);
        String second = created();

        assertThat(rejected(new ApproveCommand(second, BUYER, "tgen_shared", second + "-1", 30000)))
                .isEqualTo(PaymentError.INVALID_REQUEST);
        assertThat(status(second)).isEqualTo("CREATED");
        assertThat(pgCalls(second)).isZero();
        verifyNoInteractions(toss);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-04] 검증을 통과하면 DB의 orderId·금액과 멱등 키 confirm-{pg_order_id}로 토스 승인을 부른다")
    void callsConfirmWithStoredValues() throws Exception {
        String orderRef = created();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.NotSent("테스트"));

        approvals.approve(command(orderRef));

        verify(toss)
                .confirm("tgen_" + orderRef, orderRef + "-1", 30000L, "confirm-" + orderRef + "-1");
    }
}
