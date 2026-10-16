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
 *       transaction 이 못 쓰게 된다(PostgreSQL 은 오류가 난 transaction 의 후속 문장을 거부한다).
 *   <li><b>한 이벤트로 여러 명에게 만든다.</b> 방송 시작 알림은 신청자 전원에게 간다. 1,000명이면 1,000번 왕복하는 대신 한 번에 보낸다.
 * </ul>
 *
 * <p>중복 차단은 {@code ON CONFLICT DO NOTHING} 으로 한다. {@code WHERE NOT EXISTS} 로는 안 된다. 그 조건은 아직 커밋되지
 * 않은 다른 쓰기의 행을 보지 못해서, 같은 이벤트를 두 instance 가 동시에 처리하면 양쪽 다 "없다" 로 통과한 뒤 늦게 커밋한 쪽이 유일 제약에 걸려 터진다. 그러면
 * 알림이 유실되거나(버리면) 같은 메시지를 영원히 재시도한다.
 *
 * <p>충돌 대상 컬럼을 적지 않는 이유는 H2 가 그 문법을 지원하지 않기 때문이다. 테스트가 H2 로 같은 SQL 을 돌린다. 이 표의 유일 제약은 {@code
 * (event_id, member_id)} 하나뿐이라 대상을 적는 것과 의미가 같다.
 */
@Repository
public class NotificationWriteRepository {

    private static final String INSERT_IF_ABSENT =
            """
            INSERT INTO notification
                (member_id, event_id, event_type, title, body, link_type, link_value,
                 occurred_at, created_at, updated_at)
            VALUES (:memberId, :eventId, :eventType, :title, :body, :linkType, :linkValue,
                    :occurredAt, :now, :now)
            ON CONFLICT DO NOTHING
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
