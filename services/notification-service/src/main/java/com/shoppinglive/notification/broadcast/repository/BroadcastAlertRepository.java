package com.shoppinglive.notification.broadcast.repository;

import com.shoppinglive.notification.subscription.dto.AlertSubscription;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 방송 시작 알림 신청({@code broadcast_alert})을 읽고 쓴다.
 *
 * <p>신청과 해제는 모두 <b>여러 번 호출해도 결과가 같다</b>. 사용자가 버튼을 두 번 눌렀다고 오류를 줄 이유가 없다.
 *
 * <p>신청은 조건부 INSERT 한 문장으로 끝낸다. "있는지 보고 없으면 넣기" 를 두 문장으로 나누면 같은 사용자의 동시 요청에서 한쪽이 유일 제약에 걸려 터진다.
 */
@Repository
public class BroadcastAlertRepository {

    private static final String INSERT_IF_MISSING =
            """
            INSERT INTO broadcast_alert (member_id, broadcast_id, created_at, updated_at)
            SELECT :memberId, :broadcastId, :now, :now
            WHERE NOT EXISTS (
                SELECT 1 FROM broadcast_alert
                WHERE member_id = :memberId AND broadcast_id = :broadcastId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BroadcastAlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 이번 호출로 새로 신청됐으면 {@code true}, 이미 신청돼 있었으면 {@code false}
     */
    public boolean register(String memberId, long broadcastId) {
        Map<String, Object> params =
                Map.of(
                        "memberId", memberId,
                        "broadcastId", broadcastId,
                        "now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        return jdbc.update(INSERT_IF_MISSING, params) == 1;
    }

    /**
     * @return 이번 호출로 해제됐으면 {@code true}, 신청이 없었으면 {@code false}
     */
    public boolean unregister(String memberId, long broadcastId) {
        return jdbc.update(
                        """
                        DELETE FROM broadcast_alert
                        WHERE member_id = :memberId AND broadcast_id = :broadcastId
                        """,
                        Map.of("memberId", memberId, "broadcastId", broadcastId))
                == 1;
    }

    /** 그 방송의 신청자 전원. 방송이 시작되면 이 목록이 알림 생성의 대상이 된다. */
    public List<String> findMemberIds(long broadcastId) {
        return jdbc.queryForList(
                """
                SELECT member_id FROM broadcast_alert WHERE broadcast_id = :broadcastId
                """,
                Map.of("broadcastId", broadcastId),
                String.class);
    }

    public List<AlertSubscription> list(String memberId, Long cursor, int limit) {
        String sql =
                "SELECT broadcast_id, created_at FROM broadcast_alert WHERE member_id = :memberId"
                        + (cursor == null ? "" : " AND broadcast_id < :cursor")
                        + " ORDER BY broadcast_id DESC LIMIT :limit";
        var params = new java.util.HashMap<String, Object>();
        params.put("memberId", memberId);
        params.put("cursor", cursor);
        params.put("limit", limit);
        return jdbc.query(
                sql,
                params,
                (rs, row) ->
                        new AlertSubscription(
                                rs.getLong("broadcast_id"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    public boolean exists(String memberId, long broadcastId) {
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM broadcast_alert
                        WHERE member_id = :memberId AND broadcast_id = :broadcastId
                        """,
                        Map.of("memberId", memberId, "broadcastId", broadcastId),
                        Integer.class);
        return count != null && count > 0;
    }
}
