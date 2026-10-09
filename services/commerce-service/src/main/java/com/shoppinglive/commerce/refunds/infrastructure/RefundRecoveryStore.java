package com.shoppinglive.commerce.refunds.infrastructure;

import com.shoppinglive.commerce.refunds.domain.RefundStatus;
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

/** Short, independently committed leases; no gateway call runs under a row lock. */
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
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("claim limit must be 1..1000");
        return claim("", new Object[0], limit, leaseDuration);
    }

    public Optional<Lease> claim(long requestId, Duration leaseDuration) {
        if (requestId <= 0) throw new IllegalArgumentException("request id must be positive");
        return claim(" AND id=?", new Object[] {requestId}, 1, leaseDuration).stream().findFirst();
    }

    public boolean exists(long requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM refund_request WHERE id=?)", Boolean.class, requestId));
    }

    /** Reserve an invocation durably before sending it, including a retry after interrupted PROCESSING. */
    public Optional<Integer> beginInvocation(Lease lease) {
        List<Integer> counts = independent.execute(transaction -> jdbc.query("""
            UPDATE refund_request
               SET retry_count=CASE WHEN execution_started_at IS NULL THEN 0 ELSE retry_count+1 END,
                   status=CASE WHEN execution_started_at IS NULL THEN 'PROCESSING' ELSE 'UNKNOWN' END,
                   execution_started_at=COALESCE(execution_started_at,clock_timestamp()),updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
               AND ((execution_started_at IS NULL AND status='PROCESSING')
                    OR (execution_started_at IS NOT NULL AND retry_count<3))
            RETURNING retry_count
            """, (rs, row) -> rs.getInt(1), lease.requestId(), lease.token()));
        return counts.stream().findFirst();
    }

    public boolean unknown(Lease lease, Duration delay) {
        if (delay == null || delay.isNegative()) throw new IllegalArgumentException("delay must be nonnegative");
        return independent.execute(transaction -> jdbc.update("""
            UPDATE refund_request SET status='UNKNOWN',lease_token=NULL,lease_until=NULL,
                   execution_started_at=COALESCE(execution_started_at,clock_timestamp()),
                   next_action_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   result_detail='Mock 환불 결과 확인이 필요합니다.',updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """, delay.toMillis(), lease.requestId(), lease.token()) == 1);
    }

    private List<Lease> claim(String predicate, Object[] selection, int limit, Duration duration) {
        long millis = positiveMillis(duration);
        String token = UUID.randomUUID().toString();
        Object[] args = new Object[selection.length + 3];
        System.arraycopy(selection, 0, args, 0, selection.length);
        args[selection.length] = limit;
        args[selection.length + 1] = token;
        args[selection.length + 2] = millis;
        return independent.execute(transaction -> jdbc.query("""
            WITH candidates AS (
                SELECT id FROM refund_request
                 WHERE status IN ('PROCESSING','UNKNOWN')
                   AND next_action_at <= clock_timestamp()
                   AND (lease_until IS NULL OR lease_until <= clock_timestamp())
            """ + predicate + """
                 ORDER BY next_action_at,id
                 LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE refund_request r
               SET lease_token=?, lease_until=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   updated_at=clock_timestamp()
              FROM candidates c WHERE r.id=c.id
            RETURNING r.id,r.payment_group_id,r.refund_amount,r.status,r.idempotency_key,
                      r.request_fingerprint,r.retry_count,r.execution_started_at,r.lease_token,r.lease_until
            """, RefundRecoveryStore::mapLease, args));
    }

    /** Expired owners cannot renew a lease that another worker may acquire. */
    public boolean renew(Lease lease, Duration duration) {
        long millis = positiveMillis(duration);
        return independent.execute(transaction -> jdbc.update("""
            UPDATE refund_request SET lease_until=clock_timestamp() + (? * INTERVAL '1 millisecond'),
                   updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """, millis, lease.requestId(), lease.token()) == 1);
    }

    /** Rescheduling does not resolve a refund or change its retry count. */
    public boolean release(Lease lease, Duration delay) {
        if (delay == null || delay.isNegative()) throw new IllegalArgumentException("delay must be nonnegative");
        long millis = delay.toMillis();
        return independent.execute(transaction -> jdbc.update("""
            UPDATE refund_request SET lease_token=NULL,lease_until=NULL,
                   next_action_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),updated_at=clock_timestamp()
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN')
            """, millis, lease.requestId(), lease.token()) == 1);
    }

    private static long positiveMillis(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.toMillis() < 1) {
            throw new IllegalArgumentException("lease duration must be at least one millisecond");
        }
        return duration.toMillis();
    }

    private static Lease mapLease(ResultSet rs, int row) throws SQLException {
        Timestamp started = rs.getTimestamp("execution_started_at");
        return new Lease(rs.getLong("id"), rs.getLong("payment_group_id"), rs.getLong("refund_amount"),
            RefundStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"), rs.getInt("retry_count"),
            started == null ? null : started.toInstant(), rs.getString("lease_token"),
            rs.getTimestamp("lease_until").toInstant());
    }

    public record Lease(long requestId, long paymentGroupId, long refundAmount, RefundStatus status,
                        String idempotencyKey, String fingerprint, int retryCount,
                        Instant executionStartedAt, String token, Instant until) { }
}
