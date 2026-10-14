package com.shoppinglive.commerce.sales.event;

import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.Objects;

/**
 * Commerce → Shopping 판매정보 이벤트 계약의 전송 관련 상수.
 *
 * <p>모든 이벤트는 판매 ID를 Kafka 키로 사용하므로 같은 판매정보의 이벤트는 같은 파티션에 들어간다. 소비자는 판매정보별 {@code snapshotVersion}이
 * 마지막 반영 버전보다 클 때만 스냅샷을 반영한다. 시각과 이벤트 ID는 순서 판정에 사용하지 않는다. 재고 변경을 포함해 전체 스냅샷의 어느 필드든 변경되면 버전이 증가한다.
 */
public final class SalesInfoEventContract {

    public static final String TOPIC_NAME = "commerce.sales-info.v1";
    public static final String MESSAGE_KEY_FIELD = "salesId";
    public static final int SCHEMA_VERSION = 1;

    private SalesInfoEventContract() {}

    /** v1 봉투를 공통 형식으로 교체했다. 저장소 밖의 기존 소비자 호환성은 별도로 확인해야 한다. */
    public static EventEnvelope<SalesInfoSnapshot> create(
            SalesInfoEventType type, Instant occurredAt, SalesInfoSnapshot payload) {
        return EventEnvelope.create(
                Objects.requireNonNull(type, "eventType은 필수입니다.").name(),
                SCHEMA_VERSION,
                "commerce-service",
                messageKey(payload),
                occurredAt,
                payload);
    }

    /** 모든 이벤트 유형의 고정 Kafka 키인 판매 ID를 10진수 문자열로 반환한다. */
    public static String messageKey(SalesInfoSnapshot payload) {
        return Long.toString(Objects.requireNonNull(payload, "payload는 필수입니다.").salesId());
    }
}
