package com.shoppinglive.notification.commerce.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommerceNotificationListener {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> GROUP_RESULTS =
            Set.of(
                    "PaymentSucceeded",
                    "PaymentFailed",
                    "OrderCancelled",
                    "OrderExpired",
                    "RefundCompleted");
    private final NotificationWriteRepository notifications;

    public CommerceNotificationListener(NotificationWriteRepository notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = {"commerce.payment-group.v1", "commerce.coupon-info.v1"})
    public void consume(ConsumerRecord<String, String> record) {
        Message message = parse(record);
        if (message != null)
            notifications.createAll(message.notification(), List.of(message.memberId()));
    }

    // 봉투와 대상 식별자가 잘못된 영구 실패는 기존 DLT 정책으로 보낸다. DB 일시 실패는 재시도한다.
    private static Message parse(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = MAPPER.readTree(record.value());
            String type = text(root, "eventType");
            boolean coupon = record.topic().equals("commerce.coupon-info.v1");
            if (coupon ? !type.equals("CouponIssued") : !GROUP_RESULTS.contains(type)) return null;
            if (!text(root, "source").equals("commerce-service")
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1)
                throw invalid("지원하지 않는 이벤트 봉투입니다");
            String eventId = uuid(text(root, "eventId"));
            Instant at = Instant.parse(text(root, "occurredAt"));
            JsonNode payload = root.path("payload");
            String member = uuid(text(payload, "memberId"));
            String key = text(payload, coupon ? "couponId" : "groupNumber");
            if (!key.equals(record.key()) || !key.equals(text(root, "aggregateId")))
                throw invalid("메시지 key가 대상 식별자와 일치하지 않습니다");
            if (coupon) {
                if (key.length() > 36) throw invalid("쿠폰 ID가 올바르지 않습니다");
                String name = limited(text(payload, "name"), 120);
                long amount = number(payload, "fixedDiscount", 1);
                Instant expiry = Instant.parse(text(payload, "expiresAt"));
                return new Message(
                        member,
                        new NewNotification(
                                eventId,
                                type,
                                "쿠폰이 발급되었습니다",
                                name
                                        + " · "
                                        + money(amount)
                                        + " 할인 · "
                                        + java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy.MM.dd HH:mm")
                                                .withZone(java.time.ZoneId.of("Asia/Seoul"))
                                                .format(expiry)
                                        + "까지 사용 가능",
                                NotificationLinkType.COUPON,
                                key,
                                at));
            }
            if (!key.startsWith("PG-") || !uuid(key.substring(3)).equals(key.substring(3)))
                throw invalid("결제 묶음 번호가 올바르지 않습니다");
            number(payload, "paymentGroupId", 1);
            if (!text(payload, "currency").equals("KRW")) throw invalid("지원하지 않는 통화입니다");
            JsonNode orders = payload.path("orders");
            long count = number(payload, "orderCount", 1);
            if (!orders.isArray() || orders.size() != count) throw invalid("주문 목록이 올바르지 않습니다");
            for (JsonNode order : orders) {
                text(order, "orderNumber");
                text(order, "productName");
                number(order, "amount", 0);
            }
            String product =
                    limited(text(orders.get(0), "productName"), 120)
                            + (count > 1 ? " 외 " + (count - 1) + "건" : "");
            NotificationLinkType link = NotificationLinkType.PAYMENT_GROUP;
            String title;
            String detail;
            switch (type) {
                case "PaymentSucceeded" -> {
                    number(payload, "paymentId", 1);
                    title = "결제가 완료되었습니다";
                    detail = money(number(payload, "paidAmount", 0)) + " 결제 완료";
                }
                case "PaymentFailed" -> {
                    number(payload, "paymentId", 1);
                    number(payload, "attemptedAmount", 0);
                    title = "결제에 실패했습니다";
                    detail =
                            switch (text(payload, "failureCode")) {
                                case "TIMEOUT" -> "결제 확인이 지연되어 실패했습니다. 주문 상태를 확인해 주세요.";
                                case "INSUFFICIENT_BALANCE" -> "잔액이 부족합니다. 결제수단을 확인해 주세요.";
                                default -> "결제가 거절되었습니다. 주문 상태를 확인해 주세요.";
                            };
                }
                case "OrderCancelled" -> {
                    title = "주문이 취소되었습니다";
                    detail = money(number(payload, "cancelledAmount", 0)) + " 주문 취소";
                }
                case "OrderExpired" -> {
                    Instant.parse(text(payload, "expiresAt"));
                    title = "주문 결제 시간이 만료되었습니다";
                    detail = money(number(payload, "expiredAmount", 0)) + " 주문 만료";
                }
                case "RefundCompleted" -> {
                    long id = number(payload, "refundRequestId", 1);
                    long amount = number(payload, "refundedAmount", 0);
                    long cumulative = number(payload, "cumulativeRefundAmount", amount);
                    title = "환불이 완료되었습니다";
                    detail = money(amount) + " 환불 · 누적 " + money(cumulative);
                    link = NotificationLinkType.REFUND;
                    key += "/" + id;
                }
                default -> throw invalid("지원하지 않는 이벤트입니다");
            }
            return new Message(
                    member,
                    new NewNotification(
                            eventId, type, title, product + " · " + detail, link, key, at));
        } catch (InvalidEventMessageException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("거래 이벤트를 해석할 수 없습니다: " + e.getClass().getSimpleName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw invalid("필수 문자열이 없습니다: " + field);
        return value.textValue();
    }

    private static long number(JsonNode node, String field, long min) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min) throw invalid("정수 값이 올바르지 않습니다: " + field);
        return value.longValue();
    }

    private static String uuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw invalid("UUID가 올바르지 않습니다");
        return value;
    }

    private static String limited(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String money(long amount) {
        return String.format(java.util.Locale.KOREA, "%,d원", amount);
    }

    private static InvalidEventMessageException invalid(String reason) {
        return new InvalidEventMessageException(reason);
    }

    private record Message(String memberId, NewNotification notification) {}
}
