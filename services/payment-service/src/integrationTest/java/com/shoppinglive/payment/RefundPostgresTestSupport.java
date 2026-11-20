package com.shoppinglive.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.SellerAmountRequest;
import com.shoppinglive.payment.payment.service.PaymentService;
import com.shoppinglive.payment.refund.service.RefundIntakeService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 환불 요청 접수 테스트의 공통 도구. Core가 보내는 RefundRequested payload를 만든다. */
abstract class RefundPostgresTestSupport {
    static final String BUYER = "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13";
    static final long AMOUNT = 30000;
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired PaymentService payments;
    @Autowired RefundIntakeService intake;
    @Autowired JdbcTemplate jdbc;

    static String newOrderRef() {
        return "PG-" + UUID.randomUUID().toString().substring(0, 13);
    }

    /** CREATED 결제를 만들고 결제 ID를 돌려준다. 접수 단계는 결제 상태를 보지 않는다. */
    long newPayment(String orderRef) {
        payments.create(
                new CreatePaymentRequest(
                        orderRef,
                        UUID.fromString(BUYER),
                        AMOUNT,
                        OffsetDateTime.now().plusMinutes(10),
                        List.of(
                                new SellerAmountRequest(
                                        orderRef + "-S1",
                                        UUID.randomUUID(),
                                        AMOUNT,
                                        new BigDecimal("0.1000")))));
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
    }

    /** Core #378이 보내는 모양의 요청 payload. requestedAt은 Core처럼 나노초까지 쓴다. paymentId가 null이면 넣지 않는다. */
    static ObjectNode request(String orderRef, Long paymentId, String purpose) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("refundRequestId", UUID.randomUUID().toString());
        payload.put("orderRef", orderRef);
        if (paymentId != null) {
            payload.put("paymentId", paymentId);
        }
        payload.put("purpose", purpose);
        payload.put(
                "reason", purpose.equals("USER_REFUND") ? "CUSTOMER_REQUEST" : "LATE_APPROVAL");
        payload.put("currency", "KRW");
        payload.put("amount", 4000);
        ObjectNode seller = payload.putArray("items").addObject();
        seller.put("sellerOrderNumber", orderRef + "-S1");
        seller.put("amount", 4000);
        ObjectNode item = seller.putArray("orderItems").addObject();
        item.put("orderItemId", 301);
        item.put("quantity", 1);
        item.put("amount", 4000);
        payload.put("requestedAt", "2026-11-21T00:59:59.123456789Z");
        return payload;
    }

    /** Core가 같은 요청을 다시 보낼 때처럼 JSON 문자열을 거친 새 노드를 만든다. */
    static JsonNode copy(JsonNode node) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(node));
    }

    Map<String, Object> refund(String refundRequestId) {
        return jdbc.queryForMap(
                "SELECT * FROM refund WHERE refund_request_id = ?", refundRequestId);
    }

    int refundCount(String refundRequestId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refund WHERE refund_request_id = ?",
                Integer.class,
                refundRequestId);
    }

    List<JsonNode> results(String orderRef) throws Exception {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE topic = 'payment.refund.results.v1'"
                                + " AND message_key = ? ORDER BY created_at, id",
                        String.class,
                        orderRef);
        List<JsonNode> events = new java.util.ArrayList<>();
        for (String row : rows) {
            events.add(JSON.readTree(row));
        }
        return events;
    }
}
