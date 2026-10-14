package com.shoppinglive.commerce.payment.service;

import com.shoppinglive.commerce.payment.entity.RefundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 짧은 유효 기간의 실행 권한을 독립적으로 커밋한다. 행 잠금을 보유한 동안 게이트웨이를 호출하지 않는다. */
@Repository
public class RefundRecoveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public RefundRecoveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<Lease> claimDue(int limit, Duration leaseDuration) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("실행 권한을 확보할 항목 수는 1~1000이어야 합니다.");
        }
        return claim("", new Object[0], limit, leaseDuration);
    }

    public Optional<Lease> claim(long requestId, Duration leaseDuration) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("요청 ID는 양수여야 합니다.");
        }
        return claim(" AND id=?", new Object[] {requestId}, 1, leaseDuration).stream().findFirst();
    }

    public boolean exists(long requestId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM refund_request WHERE id=?)",
                        Boolean.class,
                        requestId));
    }

    /** 중단된 PROCESSING 작업의 재시도를 포함해 호출 전에 호출 횟수를 영속적으로 확보한다. */
    public Optional<Integer> beginInvocation(Lease lease) {
        List<Integer> counts =
                independent.execute(
                        transaction ->
                                jdbc.query(
                                        """
            UPDATE refund_request
               SET retry_count=CASE WHEN execution_started_at IS NULL THEN 0 ELSE retry_count+1 END,
                   status=CASE WHEN execution_started_at IS NULL THEN 'PROCESSING' ELSE 'UNKNOWN' END,
                   execution_started_at=COALESCE(execution_started_at,clock_timestamp()),updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
               AND ((execution_started_at IS NULL AND status='PROCESSING')
                    OR (execution_started_at IS NOT NULL AND retry_count<3))
            RETURNING retry_count
            """,
                                        (rs, row) -> rs.getInt(1),
                                        lease.requestId(),
                                        lease.token()));
        return counts.stream().findFirst();
    }

    public boolean unknown(Lease lease, Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("대기 시간은 음수일 수 없습니다.");
        }
        return independent.execute(
                transaction ->
                        jdbc.update(
                                        """
            UPDATE refund_request SET status='UNKNOWN',lease_token=NULL,lease_until=NULL,
                   execution_started_at=COALESCE(execution_started_at,clock_timestamp()),
                   next_action_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   result_detail='Mock 환불 결과 확인이 필요합니다.',updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """,
                                        delay.toMillis(),
                                        lease.requestId(),
                                        lease.token())
                                == 1);
    }

    private List<Lease> claim(String predicate, Object[] selection, int limit, Duration duration) {
        long millis = positiveMillis(duration);
        String token = UUID.randomUUID().toString();
        Object[] args = new Object[selection.length + 3];
        System.arraycopy(selection, 0, args, 0, selection.length);
        args[selection.length] = limit;
        args[selection.length + 1] = token;
        args[selection.length + 2] = millis;
        return independent.execute(
                transaction ->
                        jdbc.query(
                                """
            WITH candidates AS (
                SELECT id FROM refund_request
                 WHERE status IN ('PROCESSING','UNKNOWN')
                   AND next_action_at <= clock_timestamp()
                   AND (lease_until IS NULL OR lease_until <= clock_timestamp())
            """
                                        + predicate
                                        + """
                 ORDER BY next_action_at,id
                 LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE refund_request r
               SET lease_token=?, lease_until=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   updated_at=clock_timestamp()
              FROM candidates c WHERE r.id=c.id
            RETURNING r.id,r.payment_group_id,r.refund_amount,r.status,r.idempotency_key,
                      r.request_fingerprint,r.retry_count,r.execution_started_at,r.lease_token,r.lease_until
            """,
                                RefundRecoveryStore::mapLease,
                                args));
    }

    /** 유효 기간이 만료된 작업자는 다른 작업자가 확보할 수 있는 실행 권한을 갱신할 수 없다. */
    public boolean renew(Lease lease, Duration duration) {
        long millis = positiveMillis(duration);
        return independent.execute(
                transaction ->
                        jdbc.update(
                                        """
            UPDATE refund_request SET lease_until=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """,
                                        millis,
                                        lease.requestId(),
                                        lease.token())
                                == 1);
    }

    /** 처리 예정 시각을 바꿔도 환불 결과를 확정하거나 재시도 횟수를 변경하지 않는다. */
    public boolean release(Lease lease, Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("대기 시간은 음수일 수 없습니다.");
        }
        long millis = delay.toMillis();
        return independent.execute(
                transaction ->
                        jdbc.update(
                                        """
            UPDATE refund_request SET lease_token=NULL,lease_until=NULL,
                   next_action_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """,
                                        millis,
                                        lease.requestId(),
                                        lease.token())
                                == 1);
    }

    private static long positiveMillis(Duration duration) {
        if (duration == null
                || duration.isNegative()
                || duration.isZero()
                || duration.toMillis() < 1) {
            throw new IllegalArgumentException("실행 권한의 유효 기간은 1밀리초 이상이어야 합니다.");
        }
        return duration.toMillis();
    }

    private static Lease mapLease(ResultSet rs, int row) throws SQLException {
        Timestamp started = rs.getTimestamp("execution_started_at");
        return new Lease(
                rs.getLong("id"),
                rs.getLong("payment_group_id"),
                rs.getLong("refund_amount"),
                RefundStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getInt("retry_count"),
                started == null ? null : started.toInstant(),
                rs.getString("lease_token"),
                rs.getTimestamp("lease_until").toInstant());
    }

    public record Lease(
            long requestId,
            long paymentGroupId,
            long refundAmount,
            RefundStatus status,
            String idempotencyKey,
            String fingerprint,
            int retryCount,
            Instant executionStartedAt,
            String token,
            Instant until) {}
}
