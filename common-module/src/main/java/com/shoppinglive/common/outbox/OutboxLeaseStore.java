package com.shoppinglive.common.outbox;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** claim 관련 migration을 적용하고 명시적으로 켠 서비스만 사용한다. */
public class OutboxLeaseStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final long retryMillis;
    private final long maxRetryMillis;

    public OutboxLeaseStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Duration lease,
            Duration retry,
            Duration maxRetry) {
        if (lease.toMillis() <= 0 || retry.toMillis() <= 0 || maxRetry.compareTo(retry) < 0) {
            throw new IllegalArgumentException("Outbox lease와 재시도 간격이 올바르지 않습니다.");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lease = lease;
        this.retryMillis = retry.toMillis();
        this.maxRetryMillis = maxRetry.toMillis();
    }

    public void validateTimeout(Duration sendTimeout) {
        if (sendTimeout.isNegative() || sendTimeout.isZero() || lease.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException("Outbox lease는 전송 제한 시간보다 길어야 합니다.");
        }
    }

    public Claim claim() {
        return transactions.execute(
                status -> {
                    // 같은 토픽·키의 앞선 미발행 행이 남아 있으면 뒤의 행은 가져오지 않는다.
                    var ids =
                            jdbc.queryForList(
                                    """
                    SELECT e.id FROM event_outbox e
                    WHERE e.published_at IS NULL AND e.next_attempt_at <= CURRENT_TIMESTAMP
                      AND (e.claim_token IS NULL OR e.lease_until <= CURRENT_TIMESTAMP)
                      AND NOT EXISTS (
                        SELECT 1 FROM event_outbox earlier
                        WHERE earlier.topic=e.topic AND earlier.message_key=e.message_key
                          AND earlier.published_at IS NULL AND earlier.id < e.id)
                    ORDER BY e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                    """,
                                    Long.class);
                    if (ids.isEmpty()) return null;
                    long id = ids.getFirst();
                    String token = UUID.randomUUID().toString();
                    jdbc.update(
                            """
                    UPDATE event_outbox SET claim_token=?,
                      lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                    WHERE id=?
                    """,
                            token,
                            lease.toMillis(),
                            id);
                    return jdbc.queryForObject(
                            """
                    SELECT id,event_id,event_type,topic,message_key,payload,attempts
                    FROM event_outbox WHERE id=?
                    """,
                            (rs, row) ->
                                    new Claim(
                                            rs.getLong(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getString(6),
                                            rs.getInt(7),
                                            token),
                            id);
                });
    }

    public boolean published(Claim claim) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status ->
                                jdbc.update(
                                                """
                UPDATE event_outbox SET published_at=CURRENT_TIMESTAMP,
                  attempts=attempts+1,last_error=NULL,claim_token=NULL,lease_until=NULL
                WHERE id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP AND published_at IS NULL
                """,
                                                claim.id(),
                                                claim.token())
                                        == 1));
    }

    public boolean failed(Claim claim, String error) {
        long delay = retryMillis;
        for (int i = 0; i < claim.attempts() && delay < maxRetryMillis; i++) {
            delay =
                    delay > maxRetryMillis / 2
                            ? maxRetryMillis
                            : Math.min(delay * 2, maxRetryMillis);
        }
        long retryDelay = delay;
        String detail =
                error.length() <= OutboxMessage.MAX_ERROR_LENGTH
                        ? error
                        : error.substring(0, OutboxMessage.MAX_ERROR_LENGTH);
        return Boolean.TRUE.equals(
                transactions.execute(
                        status ->
                                jdbc.update(
                                                """
                UPDATE event_outbox SET attempts=attempts+1,last_error=?,
                  next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                  claim_token=NULL,lease_until=NULL
                WHERE id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP AND published_at IS NULL
                """,
                                                detail,
                                                retryDelay,
                                                claim.id(),
                                                claim.token())
                                        == 1));
    }

    public record Claim(
            long id,
            String eventId,
            String eventType,
            String topic,
            String messageKey,
            String payload,
            int attempts,
            String token) {}
}
