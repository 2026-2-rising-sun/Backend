package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCheckoutServiceTest extends PaymentApiTestSupport {

    @Test
    @DisplayName("[PAY-CHECKOUT-01] CREATED이고 만료 전이면 부를 때마다 checkout_seq를 올리고 새 pgOrderId를 준다(201)")
    void opensNewCheckoutEachCall() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());

        checkout(orderRef)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-2"))
                .andExpect(jsonPath("$.data.amount").value(30000))
                .andExpect(jsonPath("$.data.expiresAt").isString());
        checkout(orderRef)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-3"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT checkout_seq FROM payment WHERE order_ref = ?",
                                Integer.class,
                                orderRef))
                .isEqualTo(3);
        query(orderRef).andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-3"));
    }

    @Test
    @DisplayName("[PAY-CHECKOUT-01] pgOrderId가 비워진 CREATED 결제도 다음 회차 결제창을 연다")
    void reopensClearedCheckout() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        jdbc.update("UPDATE payment SET pg_order_id = NULL WHERE order_ref = ?", orderRef);

        checkout(orderRef)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-2"));
    }

    @Test
    @DisplayName("[PAY-CHECKOUT-02] 결제가 없으면 404 NOT_READY")
    void missingPaymentIsNotReady() throws Exception {
        checkout(newOrderRef())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    @DisplayName("[PAY-CHECKOUT-02] 만료된 CREATED 결제면 409 PAYMENT_CLOSED이고 회차를 올리지 않는다")
    void expiredPaymentIsClosed() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        expire(orderRef);

        checkout(orderRef)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checkout_seq FROM payment WHERE order_ref = ?",
                                Integer.class,
                                orderRef))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "CANCELED", "PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("[PAY-CHECKOUT-02] CREATED가 아니면 409 PAYMENT_CLOSED")
    void closedPayment(String closed) throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        setStatus(orderRef, closed);

        checkout(orderRef)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"))
                .andExpect(jsonPath("$.error.message").value("결제가 닫혔습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVING", "UNKNOWN"})
    @DisplayName("[PAY-CHECKOUT-02] 승인 처리 중·결과 불명이면 409 PAYMENT_CLOSED에 처리 중 메시지를 준다")
    void processingPayment(String processing) throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        setStatus(orderRef, processing);

        checkout(orderRef)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"))
                .andExpect(jsonPath("$.error.message").value("결제를 처리하고 있습니다."));
    }
}
