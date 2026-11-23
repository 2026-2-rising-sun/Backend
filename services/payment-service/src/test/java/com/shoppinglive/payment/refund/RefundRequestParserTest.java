package com.shoppinglive.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.refund.event.InvalidRefundRequestException;
import com.shoppinglive.payment.refund.event.RefundRequestParser;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 해석할 수 없는 요청을 DLT로 보낼 예외로 바꾸는지 본다. 업무 판단(결제 없음 등)은 하지 않는다. */
class RefundRequestParserTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "PG-22222222-2222";

    static ObjectNode event() {
        ObjectNode event = JSON.createObjectNode();
        event.put("eventId", "11111111-1111-4111-8111-000000000001");
        event.put("eventType", "RefundRequested");
        event.put("schemaVersion", 1);
        event.put("source", "commercecore-service");
        event.put("aggregateId", KEY);
        event.put("occurredAt", "2026-11-21T01:00:00Z");
        ObjectNode p = event.putObject("payload");
        p.put("refundRequestId", "late-7001");
        p.put("orderRef", KEY);
        p.put("paymentId", 7001);
        p.put("purpose", "SYSTEM_COMPENSATION");
        p.put("reason", "LATE_APPROVAL");
        p.put("currency", "KRW");
        p.put("amount", 4000);
        ObjectNode seller = p.putArray("items").addObject();
        seller.put("sellerOrderNumber", "ORDER-301");
        seller.put("amount", 4000);
        ObjectNode item = seller.putArray("orderItems").addObject();
        item.put("orderItemId", 301);
        item.put("quantity", 1);
        item.put("amount", 4000);
        p.put("requestedAt", "2026-11-21T00:59:59.123456789Z");
        return event;
    }

    static void rejects(Consumer<ObjectNode> change) {
        ObjectNode event = event();
        change.accept(event);
        assertThatThrownBy(() -> RefundRequestParser.parse(KEY, JSON.writeValueAsString(event)))
                .isInstanceOf(InvalidRefundRequestException.class);
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 계약에 맞는 요청은 payload를 고치지 않고 돌려준다")
    void returnsPayloadUnchanged() throws Exception {
        ObjectNode event = event();
        assertThat(RefundRequestParser.parse(KEY, JSON.writeValueAsString(event)))
                .isEqualTo(event.get("payload"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"깨진 JSON", "[]", "null", "{}"})
    @DisplayName("[REF-ACCEPT-07] JSON 객체 봉투가 아니면 해석할 수 없다")
    void rejectsNonEnvelope(String value) {
        assertThatThrownBy(() -> RefundRequestParser.parse(KEY, value))
                .isInstanceOf(InvalidRefundRequestException.class);
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 봉투·키가 계약과 다르면 해석할 수 없다")
    void rejectsEnvelopeMismatch() {
        rejects(e -> e.put("eventType", "RefundSucceeded"));
        rejects(e -> e.put("schemaVersion", 2));
        rejects(e -> e.put("source", "payment-service"));
        rejects(e -> e.put("eventId", "not-a-uuid"));
        rejects(e -> e.put("aggregateId", "PG-other-0000"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("orderRef", "PG-other-0000"));
        rejects(e -> e.put("extra", 1));
        assertThatThrownBy(() -> RefundRequestParser.parse(null, JSON.writeValueAsString(event())))
                .isInstanceOf(InvalidRefundRequestException.class);
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 요청 ID·목적·금액·paymentId·items가 계약과 다르면 해석할 수 없다")
    void rejectsPayloadViolations() {
        rejects(e -> ((ObjectNode) e.get("payload")).put("refundRequestId", "공백 있음"));
        rejects(e -> ((ObjectNode) e.get("payload")).remove("refundRequestId"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("purpose", "REFUND"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("amount", -1));
        rejects(e -> ((ObjectNode) e.get("payload")).put("amount", "4000"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("paymentId", 0));
        rejects(e -> ((ObjectNode) e.get("payload")).putArray("items"));
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 결과에 그대로 실릴 필드가 계약과 다르면 해석할 수 없다(Core가 결과를 거부해 파티션이 막히는 것을 막는다)")
    void rejectsFieldsCoreWouldRejectInResult() {
        rejects(e -> ((ObjectNode) e.get("payload")).put("extra", 1));
        rejects(e -> ((ObjectNode) e.get("payload")).remove("requestedAt"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("requestedAt", 1));
        rejects(e -> ((ObjectNode) e.get("payload")).remove("reason"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("reason", "OTHER"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("currency", "USD"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("purpose", "USER_REFUND"));
        rejects(e -> ((ObjectNode) e.get("payload").get("items").get(0)).put("extra", 1));
        rejects(e -> ((ObjectNode) e.get("payload").get("items").get(0)).remove("amount"));
        rejects(e -> ((ObjectNode) e.get("payload").get("items").get(0)).putArray("orderItems"));
        rejects(
                e ->
                        ((ObjectNode) e.get("payload").get("items").get(0).get("orderItems").get(0))
                                .put("quantity", 0));
        rejects(
                e ->
                        ((ObjectNode) e.get("payload").get("items").get(0).get("orderItems").get(0))
                                .put("extra", 1));
    }

    private static ObjectNode seller(ObjectNode event, int index) {
        return (ObjectNode) event.get("payload").get("items").get(index);
    }

    private static ObjectNode orderItem(ObjectNode event) {
        return (ObjectNode) seller(event, 0).get("orderItems").get(0);
    }

    private static void addSeller(ObjectNode event, String number, long itemId, long amount) {
        ObjectNode added =
                ((com.fasterxml.jackson.databind.node.ArrayNode) event.get("payload").get("items"))
                        .addObject();
        added.put("sellerOrderNumber", number);
        added.put("amount", amount);
        ObjectNode item = added.putArray("orderItems").addObject();
        item.put("orderItemId", itemId);
        item.put("quantity", 1);
        item.put("amount", amount);
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 중복 ID·합계 불일치·목적과 사유 조합 위반·날짜가 아닌 requestedAt은 해석할 수 없다")
    void rejectsOriginalsCoreWouldReject() {
        rejects(
                e -> {
                    ObjectNode duplicate = orderItem(e).deepCopy();
                    ((com.fasterxml.jackson.databind.node.ArrayNode) seller(e, 0).get("orderItems"))
                            .add(duplicate);
                    seller(e, 0).put("amount", 8000);
                    ((ObjectNode) e.get("payload")).put("amount", 8000);
                });
        rejects(
                e -> {
                    addSeller(e, "ORDER-302", 301, 1000); // 판매자가 달라도 주문 항목 ID는 전체에서 하나다
                    ((ObjectNode) e.get("payload")).put("amount", 5000);
                });
        rejects(
                e -> {
                    addSeller(e, "ORDER-301", 302, 1000);
                    ((ObjectNode) e.get("payload")).put("amount", 5000);
                });
        rejects(e -> orderItem(e).put("amount", 3999));
        rejects(e -> ((ObjectNode) e.get("payload")).put("amount", 3999));
        rejects(
                e -> {
                    addSeller(e, "ORDER-302", 302, Long.MAX_VALUE);
                    ((ObjectNode) e.get("payload")).put("amount", 3999);
                });
        rejects(e -> ((ObjectNode) e.get("payload")).put("reason", "CUSTOMER_REQUEST"));
        rejects(e -> ((ObjectNode) e.get("payload")).put("requestedAt", "not-a-date"));
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] paymentId가 없거나 계약 밖 사유면 해석할 수 없다(#384 계약: 사유 3개, paymentId 필수)")
    void rejectsMissingPaymentIdAndReasonsOutsideContract() {
        rejects(e -> ((ObjectNode) e.get("payload")).remove("paymentId"));
        for (String reason : new String[] {"ORDER_EXPIRED", "ORDER_CANCELLED", "APPROVAL_RESPONSE_LOST"}) {
            rejects(
                    e -> {
                        ((ObjectNode) e.get("payload")).put("reason", reason);
                    });
        }
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 0원 항목과 0원 요청은 정상이다")
    void acceptsZeroAmounts() throws Exception {
        ObjectNode zeroItem = event();
        addSeller(zeroItem, "ORDER-302", 302, 0);
        assertThat(RefundRequestParser.parse(KEY, JSON.writeValueAsString(zeroItem)))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(zeroItem)).get("payload"));

        ObjectNode zeroRequest = event();
        orderItem(zeroRequest).put("amount", 0);
        seller(zeroRequest, 0).put("amount", 0);
        ((ObjectNode) zeroRequest.get("payload")).put("amount", 0);
        assertThat(RefundRequestParser.parse(KEY, JSON.writeValueAsString(zeroRequest)))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(zeroRequest)).get("payload"));
    }
}
