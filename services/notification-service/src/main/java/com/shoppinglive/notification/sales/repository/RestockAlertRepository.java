package com.shoppinglive.notification.sales.repository;

import com.shoppinglive.notification.subscription.dto.AlertSubscription;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재입고 알림 신청({@code restock_alert})을 읽고 쓴다.
 *
 * <p>신청과 해제는 모두 여러 번 호출해도 결과가 같다. 신청은 {@code ON CONFLICT DO NOTHING} 한 문장으로 끝낸다. "있는지 보고 없으면 넣기" 로는
 * 안 된다. 그 조건은 아직 커밋되지 않은 다른 요청의 행을 보지 못해서, 같은 사용자가 버튼을 빠르게 두 번 누르면 양쪽 다 통과한 뒤 늦은 쪽이 유일 제약에 걸려 500 이
 * 난다.
 *
 * <p>충돌 대상 컬럼을 적지 않는 이유는 H2 가 그 문법을 지원하지 않기 때문이다. 이 표의 유일 제약은 {@code (member_id, product_id)} 하나뿐이라
 * 의미가 같다.
 */
@Repository
public class RestockAlertRepository {

    private static final String INSERT_IF_MISSING =
            """
            INSERT INTO restock_alert (member_id, product_id, created_at, updated_at)
            VALUES (:memberId, :productId, :now, :now)
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RestockAlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean register(String memberId, long productId) {
        return jdbc.update(
                        INSERT_IF_MISSING,
                        Map.of(
                                "memberId", memberId,
                                "productId", productId,
                                "now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)))
                == 1;
    }

    public boolean unregister(String memberId, long productId) {
        return delete(
                        """
                        DELETE FROM restock_alert
                        WHERE member_id = :memberId AND product_id = :productId
                        """,
                        Map.of("memberId", memberId, "productId", productId))
                == 1;
    }

    public record Subscription(long id, String memberId) {}

    public List<Subscription> findSubscriptions(long productId) {
        return jdbc.query(
                "SELECT id, member_id FROM restock_alert WHERE product_id = :productId",
                Map.of("productId", productId),
                (rs, row) -> new Subscription(rs.getLong("id"), rs.getString("member_id")));
    }

    /** 조회 뒤 들어온 신청과 해제 후 재신청을 보존하도록, 알림 대상이었던 행만 지운다. */
    public int deleteSubscriptions(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        return delete("DELETE FROM restock_alert WHERE id IN (:ids)", Map.of("ids", ids));
    }

    public List<AlertSubscription> list(String memberId, Long cursor, int limit) {
        String sql =
                "SELECT product_id, created_at FROM restock_alert WHERE member_id = :memberId"
                        + (cursor == null ? "" : " AND product_id < :cursor")
                        + " ORDER BY product_id DESC LIMIT :limit";
        var params = new java.util.HashMap<String, Object>();
        params.put("memberId", memberId);
        params.put("cursor", cursor);
        params.put("limit", limit);
        return jdbc.query(
                sql,
                params,
                (rs, row) ->
                        new AlertSubscription(
                                rs.getLong("product_id"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    public boolean exists(String memberId, long productId) {
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM restock_alert
                        WHERE member_id = :memberId AND product_id = :productId
                        """,
                        Map.of("memberId", memberId, "productId", productId),
                        Integer.class);
        return count != null && count > 0;
    }

    private int delete(String sql, Map<String, Object> params) {
        return jdbc.update(sql, params);
    }
}
