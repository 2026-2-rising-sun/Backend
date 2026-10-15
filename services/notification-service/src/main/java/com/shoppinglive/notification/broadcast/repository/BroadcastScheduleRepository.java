package com.shoppinglive.notification.broadcast.repository;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 방송 일정 사본({@code broadcast_schedule})을 읽고 쓴다. 방송 하나에 행 하나다.
 *
 * <p>이벤트는 중복되거나 순서가 바뀌어 올 수 있어, 저장된 값보다 큰 {@code broadcastVersion} 만 반영한다. 그러면 같은 이벤트가 두 번 와도 0건
 * 갱신으로 끝나고, 늦게 도착한 옛 일정이 새 일정을 덮지 않는다.
 *
 * <p><b>transaction 안에서 {@link #apply} 를 부르면 안 된다.</b> 동시 최초 INSERT 의 키 충돌을 잡은 뒤 UPDATE 로 이어 가는데,
 * PostgreSQL 은 오류가 난 transaction 의 후속 문장을 모두 거부한다. (Shopping 의 판매정보 사본과 같은 제약이다)
 */
@Repository
public class BroadcastScheduleRepository {

    private static final String INSERT_IF_MISSING =
            """
            INSERT INTO broadcast_schedule
                (broadcast_id, broadcast_version, title, scheduled_at, created_at, updated_at)
            SELECT :broadcastId, :broadcastVersion, :title, :scheduledAt, :now, :now
            WHERE NOT EXISTS (
                SELECT 1 FROM broadcast_schedule WHERE broadcast_id = :broadcastId)
            """;
    private static final String UPDATE_IF_NEWER =
            """
            UPDATE broadcast_schedule
            SET broadcast_version = :broadcastVersion, title = :title,
                scheduled_at = :scheduledAt, updated_at = :now
            WHERE broadcast_id = :broadcastId AND broadcast_version < :broadcastVersion
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BroadcastScheduleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 없으면 INSERT, 있으면 version 이 더 클 때만 UPDATE 한다.
     *
     * @return 행이 바뀌었으면 {@code true}. 중복·역순 이벤트는 {@code false}
     */
    public boolean apply(BroadcastSchedule schedule) {
        int inserted;
        try {
            inserted = jdbc.update(INSERT_IF_MISSING, params(schedule));
        } catch (DuplicateKeyException e) {
            // NOT EXISTS 는 다른 쓰기의 미커밋 행을 보지 못한다. 같은 방송을 동시에 INSERT 하면 한쪽은
            // PK 충돌이 난다. 먼저 들어간 행과 version 을 비교해야 하므로 UPDATE 로 넘어간다.
            inserted = 0;
        }
        return inserted == 1 || jdbc.update(UPDATE_IF_NEWER, params(schedule)) == 1;
    }

    public Optional<BroadcastSchedule> findById(long broadcastId) {
        return jdbc
                .query(
                        """
                        SELECT broadcast_id, broadcast_version, title, scheduled_at
                        FROM broadcast_schedule WHERE broadcast_id = :broadcastId
                        """,
                        Map.of("broadcastId", broadcastId),
                        (rs, rowNum) ->
                                new BroadcastSchedule(
                                        rs.getLong("broadcast_id"),
                                        rs.getLong("broadcast_version"),
                                        rs.getString("title"),
                                        rs.getObject("scheduled_at", OffsetDateTime.class)
                                                .toInstant()))
                .stream()
                .findFirst();
    }

    private static Map<String, Object> params(BroadcastSchedule schedule) {
        return Map.of(
                "broadcastId", schedule.broadcastId(),
                "broadcastVersion", schedule.broadcastVersion(),
                "title", schedule.title(),
                "scheduledAt", OffsetDateTime.ofInstant(schedule.scheduledAt(), ZoneOffset.UTC),
                "now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }
}
