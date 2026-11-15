package com.shoppinglive.payment;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentQueryControllerTest extends PaymentApiTestSupport {

    @Test
    @DisplayName("[PAY-QUERY-01] orderRef로 상태·금액·환불액·pgOrderId를 조회하고 승인 전 승인 시각은 null이다")
    void queriesCreatedPayment() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());

        query(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").isNumber())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.amount").value(30000))
                .andExpect(jsonPath("$.data.refundedAmount").value(0))
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-1"))
                .andExpect(jsonPath("$.data.approvedAt").value(nullValue()));
    }

    @Test
    @DisplayName("[PAY-QUERY-01] 승인된 결제는 승인 시각을 준다")
    void queriesApprovedPayment() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        Instant approvedAt = Instant.parse("2026-11-10T05:01:02Z");
        jdbc.update(
                "UPDATE payment SET status = 'APPROVED', approved_at = ? WHERE order_ref = ?",
                Timestamp.from(approvedAt),
                orderRef);

        query(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedAt").value("2026-11-10T05:01:02Z"));
    }

    @Test
    @DisplayName("[PAY-QUERY-01] 결제가 없으면 404 PAYMENT_NOT_FOUND")
    void missingPayment() throws Exception {
        query(newOrderRef())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_FOUND"));
    }
}
