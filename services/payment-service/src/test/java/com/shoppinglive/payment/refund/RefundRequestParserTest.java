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
}
