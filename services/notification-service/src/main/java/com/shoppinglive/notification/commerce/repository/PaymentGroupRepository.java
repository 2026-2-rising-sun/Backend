package com.shoppinglive.notification.commerce.repository;

import com.shoppinglive.notification.commerce.entity.PaymentGroup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 결제 묶음 사본({@code payment_group})을 읽고 쓴다. 묶음 하나에 행 하나다.
 *
 * <p>저장은 {@code ON CONFLICT DO NOTHING} 으로 한다. 같은 묶음 생성 이벤트가 두 번 와도 두 번째는 0건으로 끝나고, 두 instance 가
 * 동시에 받아도 유일 제약 오류가 나지 않는다. 충돌 대상 컬럼을 적지 않는 이유는 H2 가 그 문법을 지원하지 않기 때문이고, 이 표의 유일 제약은 PK 하나뿐이라 의미가
 * 같다.
 *
 * <p>묶음은 생성 뒤 내용이 바뀌지 않으므로 UPDATE 로 이어 갈 필요가 없다. 늦게 온 중복은 무시하면 그만이다.
 */
@Repository
public class PaymentGroupRepository {

    private static final String INSERT_IF_MISSING =
            """
            INSERT INTO payment_group
                (group_number, member_id, product_summary, expires_at, created_at, updated_at)
            VALUES (:groupNumber, :memberId, :productSummary, :expiresAt, :now, :now)
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentGroupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 이번 호출로 사본이 생겼으면 {@code true}, 이미 있었으면 {@code false}
     */
    public boolean save(PaymentGroup group) {
        return jdbc.update(
                        INSERT_IF_MISSING,
                        Map.of(
                                "groupNumber", group.groupNumber(),
                                "memberId", group.memberId(),
                                "productSummary", group.productSummary(),
                                "expiresAt", utc(group.expiresAt()),
                                "now", utc(Instant.now())))
                == 1;
    }

    /**
     * 묶음이 끝났다고 표시한다. 이 뒤로는 임박 알림 대상에서 빠진다.
     *
     * <p>사본이 없으면 아무것도 하지 않는다. 구독을 시작하기 전에 만들어진 묶음은 사본이 없고, 그런 묶음은 임박 알림 대상도 아니라서 표시할 행이 없어도 문제가 없다.
     *
     * <p>이미 표시돼 있으면 덮지 않는다. 같은 이벤트가 두 번 와도 처음 끝난 시각이 남는다.
     *
     * @return 이번 호출로 표시했으면 {@code true}
     */
    public boolean close(String groupNumber, Instant closedAt) {
        return jdbc.update(
                        """
                        UPDATE payment_group
                        SET closed_at = :closedAt, updated_at = :closedAt
                        WHERE group_number = :groupNumber AND closed_at IS NULL
                        """,
                        Map.of("groupNumber", groupNumber, "closedAt", utc(closedAt)))
                == 1;
    }

    /**
     * 곧 만료되는데 아직 임박 알림을 보내지 않았고, 끝나지도 않은 묶음.
     *
     * <p>{@code from} 보다 뒤에 만료되는 묶음만 가져온다. 서버가 멈췄다 복구된 뒤에 이미 만료 시각이 지난 묶음의 "곧 만료됩니다" 가 쏟아지면 안 된다.
     */
    public List<PaymentGroup> findExpiringSoon(Instant from, Instant until, int limit) {
        return jdbc.query(
                """
                SELECT group_number, member_id, product_summary, expires_at
                FROM payment_group
                WHERE expiring_soon_sent_at IS NULL
                  AND closed_at IS NULL
                  AND expires_at > :from AND expires_at <= :until
                ORDER BY expires_at
                LIMIT :limit
                """,
                Map.of("from", utc(from), "until", utc(until), "limit", limit),
                (rs, rowNum) -> toGroup(rs));
    }

    /**
     * 임박 알림을 보냈다고 표시한다. 먼저 차지한 쪽만 1을 받는다.
     *
     * @return 이번 호출로 표시했으면 {@code true}, 이미 표시돼 있었으면 {@code false}
     */
    public boolean markExpiringSoonSent(String groupNumber, Instant sentAt) {
        return jdbc.update(
                        """
                        UPDATE payment_group
                        SET expiring_soon_sent_at = :sentAt, updated_at = :sentAt
                        WHERE group_number = :groupNumber AND expiring_soon_sent_at IS NULL
                          AND closed_at IS NULL AND expires_at > :sentAt
                        """,
                        Map.of("groupNumber", groupNumber, "sentAt", utc(sentAt)))
                == 1;
    }

    private static PaymentGroup toGroup(ResultSet rs) throws SQLException {
        return new PaymentGroup(
                rs.getString("group_number"),
                rs.getString("member_id"),
                rs.getString("product_summary"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
