package com.shoppinglive.notification.commerce.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.notification.commerce.entity.PaymentGroup;
import com.shoppinglive.notification.commerce.repository.PaymentGroupRepository;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 결제 묶음 사본을 이벤트에 맞춰 갱신한다. 알림은 만들지 않는다.
 *
 * <p>만료 임박 알림은 이벤트가 오는 순간이 아니라 만료 5분 전에 나간다. 그래서 "언제 만료되는 묶음이 결제를 기다리고 있는가" 를 들고 있어야 하고, 이 클래스가 그
 * 사본을 맡는다. 실제로 알림을 만드는 것은 스케줄러다.
 *
 * <p><b>리스너를 따로 만들지 않고 {@link CommerceNotificationListener} 에 붙인 이유</b> — consumer group 이 하나라서 같은
 * 토픽에 리스너를 둘 두면 Kafka 가 파티션을 나눠 배정한다. 메시지가 둘 중 한쪽에만 가서 어떤 묶음은 사본이 생기고 어떤 묶음은 안 생긴다.
 *
 * <p>검증을 직접 한다. {@code CommerceNotificationListener} 의 검증은 알림을 만드는 종류에만 걸리고, 묶음 생성 이벤트는 종류 목록에서 빠져
 * 봉투 검사 전에 돌아간다. 사본 컬럼에 들어갈 값은 여기서 확인해야 한다.
 */
@Component
public class PaymentGroupTracker {

    static final String TOPIC = "commerce.payment-group.v1";
    static final String CREATED = "PaymentGroupCreated";

    /**
     * 묶음이 끝나는 이벤트. 이 뒤로는 임박 알림을 보내면 안 된다.
     *
     * <p>결제 실패는 넣지 않는다. 만료 전까지 다시 시도할 수 있어서 묶음이 끝난 것이 아니고, 오히려 그 사람에게 임박 알림이 가장 필요하다.
     */
    private static final Set<String> CLOSING =
            Set.of("PaymentSucceeded", "OrderCancelled", "OrderExpired");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int GROUP_NUMBER_MAX = 40;
    private static final int MEMBER_ID_MAX = 36;
    private static final int SUMMARY_NAME_MAX = 120;

    private final PaymentGroupRepository groups;

    public PaymentGroupTracker(PaymentGroupRepository groups) {
        this.groups = groups;
    }

    /** 쓰지 않는 토픽과 종류는 조용히 넘어간다. 한 토픽에 여러 종류가 섞여 온다. */
    public void track(ConsumerRecord<String, String> record) {
        if (!TOPIC.equals(record.topic())) {
            return;
        }
        JsonNode root = readRoot(record.value());
        String eventType = textOf(root, "eventType");
        if (!CREATED.equals(eventType) && !CLOSING.contains(eventType)) {
            return;
        }
        JsonNode payload = root.path("payload");
        String groupNumber = groupNumberOf(payload, record);
        if (CLOSING.contains(eventType)) {
            groups.close(groupNumber, instantOf(root, "occurredAt"));
            return;
        }
        groups.save(
                new PaymentGroup(
                        groupNumber,
                        memberIdOf(payload),
                        productSummary(payload),
                        instantOf(payload, "expiresAt")));
    }

    /** key 는 파티션을 정한다. 어긋나면 같은 묶음의 이벤트가 흩어져 순서 보장이 깨진 것이다. */
    private static String groupNumberOf(JsonNode payload, ConsumerRecord<String, String> record) {
        String groupNumber = textOf(payload, "groupNumber");
        if (!groupNumber.equals(record.key())) {
            throw invalid("메시지 key가 결제 묶음 번호와 일치하지 않습니다");
        }
        if (groupNumber.length() > GROUP_NUMBER_MAX) {
            throw invalid("결제 묶음 번호가 너무 깁니다");
        }
        return groupNumber;
    }

    private static String memberIdOf(JsonNode payload) {
        String memberId = textOf(payload, "memberId");
        if (memberId.length() > MEMBER_ID_MAX) {
            throw invalid("회원 ID가 너무 깁니다");
        }
        return memberId;
    }

    /**
     * 알림 문구에 쓸 "가을 니트 외 2건". 받을 때 한 번 줄여 사본에 넣는다.
     *
     * <p>주문 목록 전체를 들고 있을 이유가 없다. 임박 알림이 보여줄 것은 어느 묶음인지 알아볼 수 있는 이름 하나뿐이다.
     */
    private static String productSummary(JsonNode payload) {
        JsonNode orders = payload.path("orders");
        if (!orders.isArray() || orders.isEmpty()) {
            throw invalid("주문 목록이 비어 있습니다");
        }
        String first = textOf(orders.get(0), "productName");
        if (first.length() > SUMMARY_NAME_MAX) {
            first = first.substring(0, SUMMARY_NAME_MAX - 1) + "…";
        }
        return orders.size() > 1 ? first + " 외 " + (orders.size() - 1) + "건" : first;
    }

    private static JsonNode readRoot(String raw) {
        if (raw == null) {
            throw invalid("메시지가 비어 있습니다");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                throw invalid("메시지가 JSON 객체가 아닙니다");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw invalid("JSON 을 읽을 수 없습니다");
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("필수 문자열이 없습니다: " + field);
        }
        return value.textValue();
    }

    private static Instant instantOf(JsonNode node, String field) {
        try {
            return Instant.parse(textOf(node, field));
        } catch (DateTimeException e) {
            throw invalid("시각 형식이 올바르지 않습니다: " + field);
        }
    }

    private static InvalidEventMessageException invalid(String reason) {
        return new InvalidEventMessageException(reason);
    }
}
