package com.shoppinglive.notification.inbox.repository;

import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 이벤트를 받아 알림을 만든다. 읽기는 {@link NotificationRepository} 가 한다.
 *
 * <p>JPA 를 쓰지 않는 이유는 두 가지다.
 *
 * <ul>
 *   <li><b>중복을 예외 없이 걸러야 한다.</b> 같은 이벤트가 두 번 와도 두 번째는 조용히 넘어가야 하는데, 유일 제약 위반을 예외로 받아 잡으면 그
 *       transaction 이 못 쓰게 된다(PostgreSQL 은 오류가 난 transaction 의 후속 문장을 거부한다). 조건부 INSERT 한 문장이면 애초에
 *       예외가 나지 않는다.
 *   <li><b>한 이벤트로 여러 명에게 만든다.</b> 방송 시작 알림은 신청자 전원에게 간다. 1,000명이면 1,000번 왕복하는 대신 한 번에 보낸다.
 * </ul>
 */
@Repository
public class NotificationWriteRepository {

    private static final String INSERT_IF_ABSENT =
            """
            INSERT INTO notification
                (member_id, event_id, event_type, title, body, link_type, link_value,
                 occurred_at, created_at, updated_at)
            SELECT :memberId, :eventId, :eventType, :title, :body, :linkType, :linkValue,
                   :occurredAt, :now, :now
            WHERE NOT EXISTS (
                SELECT 1 FROM notification
                WHERE event_id = :eventId AND member_id = :memberId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 받는 사람마다 알림 1건을 만든다. 이미 같은 이벤트로 만든 사람은 건너뛴다.
     *
     * @param memberIds 받는 사람. 비어 있으면 아무것도 하지 않는다
     * @return 이번 호출로 새로 만들어진 알림 수
     */
    public int createAll(NewNotification notification, Collection<String> memberIds) {
        if (memberIds.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        SqlParameterSource[] batch =
                memberIds.stream()
                        .map(memberId -> params(notification, memberId, now))
                        .toArray(SqlParameterSource[]::new);
        return Arrays.stream(jdbc.batchUpdate(INSERT_IF_ABSENT, batch)).sum();
    }

    private static SqlParameterSource params(
            NewNotification notification, String memberId, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("memberId", memberId)
                .addValue("eventId", notification.eventId())
                .addValue("eventType", notification.eventType())
                .addValue("title", notification.title())
                .addValue("body", notification.body())
                .addValue("linkType", linkTypeName(notification.linkType()))
                .addValue("linkValue", notification.linkValue())
                .addValue(
                        "occurredAt",
                        OffsetDateTime.ofInstant(notification.occurredAt(), ZoneOffset.UTC))
                .addValue("now", now);
    }

    private static String linkTypeName(NotificationLinkType linkType) {
        return linkType == null ? null : linkType.name();
    }
}
