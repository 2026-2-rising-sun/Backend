package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCreateServiceTest extends PaymentApiTestSupport {

    @Test
    @DisplayName("[PAY-CREATE-01] 새 orderRef로 생성하면 CREATED 결제와 판매자별 금액을 저장하고 첫 pgOrderId를 준다(201)")
    void createsNewPayment() throws Exception {
        String orderRef = newOrderRef();
        String expiresAt = future();

        createJson(body(orderRef, BUYER, 30000, expiresAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").isNumber())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-1"))
                .andExpect(jsonPath("$.data.amount").value(30000))
                .andExpect(jsonPath("$.data.expiresAt").value(expiresAt));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT buyer_id, amount, status, checkout_seq, pg_provider, refunded_amount,"
                                + " created_at, updated_at FROM payment WHERE order_ref = ?",
                        orderRef);
        assertThat(row.get("BUYER_ID")).isEqualTo(BUYER);
        assertThat(((Number) row.get("AMOUNT")).longValue()).isEqualTo(30000L);
        assertThat(row.get("STATUS")).isEqualTo("CREATED");
        assertThat(((Number) row.get("CHECKOUT_SEQ")).intValue()).isEqualTo(1);
        assertThat(row.get("PG_PROVIDER")).isEqualTo("TOSS");
        assertThat(((Number) row.get("REFUNDED_AMOUNT")).longValue()).isZero();
        assertThat(row.get("CREATED_AT")).isNotNull();
        assertThat(row.get("UPDATED_AT")).isNotNull();

        var sellers =
                jdbc.queryForList(
                        "SELECT s.seller_order_number, s.seller_id, s.amount, s.commission_rate"
                                + " FROM payment_seller_amount s JOIN payment p ON p.id = s.payment_id"
                                + " WHERE p.order_ref = ? ORDER BY s.seller_order_number",
                        orderRef);
        assertThat(sellers).hasSize(2);
        assertThat(sellers.get(0).get("SELLER_ORDER_NUMBER")).isEqualTo(orderRef + "-S1");
        assertThat(sellers.get(0).get("SELLER_ID")).isEqualTo(SELLER);
        assertThat(((Number) sellers.get(0).get("AMOUNT")).longValue()).isEqualTo(20000L);
        assertThat((BigDecimal) sellers.get(0).get("COMMISSION_RATE")).isEqualByComparingTo("0.1");
        assertThat(((Number) sellers.get(1).get("AMOUNT")).longValue()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("[PAY-CREATE-02] 같은 orderRef·금액·구매자로 다시 생성하면 기존 CREATED 결제를 200으로 돌려주고 1건만 남는다")
    void sameRequestReturnsExistingPayment() throws Exception {
        String orderRef = newOrderRef();
        Number id =
                com.jayway.jsonpath.JsonPath.read(
                        create(orderRef).andReturn().getResponse().getContentAsString(),
                        "$.data.paymentId");

        // expiresAt·sellerAmounts가 달라도 비교하지 않고 처음 값을 유지한다(설계 미결정 1)
        createJson(body(orderRef, BUYER, 30000, Instant.now().plusSeconds(1200).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(id.longValue()))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.pgOrderId").value(orderRef + "-1"));

        assertThat(count("payment", orderRef)).isEqualTo(1);
        assertThat(sellerCount(orderRef)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVING", "UNKNOWN", "APPROVED"})
    @DisplayName("[PAY-CREATE-02] 기존 결제가 처리 중·결과 불명·승인이면 같은 요청에 현재 상태를 200으로 돌려준다")
    void sameRequestReturnsCurrentStatus(String current) throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        setStatus(orderRef, current);

        create(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(current));
        assertThat(count("payment", orderRef)).isEqualTo(1);
    }

    @Test
    @DisplayName("[PAY-CREATE-02] pgOrderId가 비워진 CREATED 결제는 재생성 응답에 pgOrderId null을 준다")
    void clearedCheckoutReturnsNullPgOrderId() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        jdbc.update("UPDATE payment SET pg_order_id = NULL WHERE order_ref = ?", orderRef);

        create(orderRef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pgOrderId").value(nullValue()));
    }

    @Test
    @DisplayName("[PAY-CREATE-03] 같은 orderRef에 금액이 다르면 409 AMOUNT_CONFLICT")
    void differentAmountConflicts() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());

        createJson(body(orderRef, BUYER, 31000, future()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AMOUNT_CONFLICT"));
    }

    @Test
    @DisplayName("[PAY-CREATE-03] 같은 orderRef에 구매자가 다르면 409 AMOUNT_CONFLICT")
    void differentBuyerConflicts() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());

        createJson(body(orderRef, OTHER_BUYER, 30000, future()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AMOUNT_CONFLICT"));
    }

    @Test
    @DisplayName("[PAY-CREATE-03] 닫힌 결제여도 금액이 다르면 AMOUNT_CONFLICT가 먼저다")
    void amountConflictBeforeClosed() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        setStatus(orderRef, "CANCELED");

        createJson(body(orderRef, BUYER, 31000, future()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AMOUNT_CONFLICT"));
    }

    @Test
    @DisplayName("[PAY-CREATE-04] 판매자별 금액 합이 amount와 다르면 400 SUM_MISMATCH이고 저장하지 않는다")
    void sumMismatch() throws Exception {
        String orderRef = newOrderRef();
        String json =
                body(orderRef, BUYER, 30000, future())
                        .replace("\"amount\":10000", "\"amount\":9999");

        createJson(json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUM_MISMATCH"));
        assertThat(count("payment", orderRef)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OD-12", "OD#123456", "OD 123456", "가나다라마바사"})
    @DisplayName("[PAY-CREATE-05] orderRef 형식이 틀리면 400 INVALID_ORDER_REF")
    void invalidOrderRef(String orderRef) throws Exception {
        createJson(body(orderRef, BUYER, 30000, future()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ORDER_REF"));
    }

    @Test
    @DisplayName("[PAY-CREATE-05] orderRef 길이는 6–50자다(5·51자 거절, 6·50자 허용)")
    void orderRefLengthBoundary() throws Exception {
        createJson(body("A".repeat(5), BUYER, 30000, future()))
                .andExpect(jsonPath("$.error.code").value("INVALID_ORDER_REF"));
        createJson(body("B".repeat(51), BUYER, 30000, future()))
                .andExpect(jsonPath("$.error.code").value("INVALID_ORDER_REF"));
        String six = newOrderRef().substring(3, 9);
        createJson(body(six, BUYER, 30000, future())).andExpect(status().isCreated());
        String fifty = (newOrderRef() + "_" + "x".repeat(50)).substring(0, 50);
        createJson(body(fifty, BUYER, 30000, future()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pgOrderId").value(fifty + "-1"));
    }

    @Test
    @DisplayName("[PAY-CREATE-05] expiresAt이 현재 이하면 400 INVALID_EXPIRES_AT")
    void pastExpiresAt() throws Exception {
        String orderRef = newOrderRef();
        createJson(body(orderRef, BUYER, 30000, Instant.now().minusSeconds(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EXPIRES_AT"));
        assertThat(count("payment", orderRef)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("[PAY-CREATE-06] 기존 결제가 취소·부분 환불·환불이면 409 PAYMENT_CLOSED")
    void closedPayment(String closed) throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        setStatus(orderRef, closed);

        create(orderRef)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"));
    }

    @Test
    @DisplayName("[PAY-CREATE-06] 만료된 CREATED 결제면 409 PAYMENT_CLOSED")
    void expiredCreatedPayment() throws Exception {
        String orderRef = newOrderRef();
        create(orderRef).andExpect(status().isCreated());
        expire(orderRef);

        // 요청의 expiresAt은 미래여야 형식 검사(INVALID_EXPIRES_AT)를 지난다
        create(orderRef)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_CLOSED"));
    }

    @Test
    @DisplayName("요청 안에서 판매자 주문 번호가 겹치면 400 INVALID_REQUEST")
    void duplicateSellerOrderNumberInRequest() throws Exception {
        String orderRef = newOrderRef();
        String json =
                body(orderRef, BUYER, 30000, future()).replace(orderRef + "-S2", orderRef + "-S1");

        createJson(json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(count("payment", orderRef)).isZero();
    }

    @Test
    @DisplayName("판매자 주문 번호가 다른 결제에 이미 있으면 400 INVALID_REQUEST이고 새 결제를 남기지 않는다")
    void sellerOrderNumberUsedByOtherPayment() throws Exception {
        String first = newOrderRef();
        create(first).andExpect(status().isCreated());
        String second = newOrderRef();
        String json = body(second, BUYER, 30000, future()).replace(second + "-S2", first + "-S2");

        createJson(json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(count("payment", second)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"buyerId\":\"" + BUYER + "\"|\"buyerId\":\"not-a-uuid\"",
                "\"amount\":30000|\"amount\":0",
                "\"commissionRate\":0.1000|\"commissionRate\":1.5",
                "\"commissionRate\":0.1000|\"commissionRate\":0.12345",
                "Z\"|\"",
                "\"sellerAmounts\":[|\"sellerAmounts\":[],\"x\":["
            })
    @DisplayName("요청 형식이 틀리면 400 INVALID_REQUEST(UUID·금액·수수료율·오프셋 없는 시각·빈 판매자 목록)")
    void invalidRequestFields(String replacement) throws Exception {
        String[] pair = replacement.split("\\|");
        String json = body(newOrderRef(), BUYER, 30000, future()).replace(pair[0], pair[1]);

        createJson(json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    int count(String table, String orderRef) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE order_ref = ?", Integer.class, orderRef);
    }

    int sellerCount(String orderRef) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_seller_amount s JOIN payment p ON p.id = s.payment_id"
                        + " WHERE p.order_ref = ?",
                Integer.class,
                orderRef);
    }
}
