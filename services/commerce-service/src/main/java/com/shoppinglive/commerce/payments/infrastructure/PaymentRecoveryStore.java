package com.shoppinglive.commerce.payments.infrastructure;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;
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

/** Independently committed ownership and invocation budget, with no provider call under a lock. */
@Repository
public class PaymentRecoveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final String now;

    public PaymentRecoveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // H2 exists only for the existing unit/HTTP fixtures; production uses the live PostgreSQL clock.
        String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        now = "PostgreSQL".equals(product) ? "clock_timestamp()" : "CURRENT_TIMESTAMP";
    }

    public List<Lease> claimDue(int limit, Duration duration) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return claim("", new Object[0], limit, duration);
    }

    public Optional<Lease> claim(long id, Duration duration) {
        if (id < 1) throw new IllegalArgumentException("payment id must be positive");
        return claim(" AND id=?", new Object[]{id}, 1, duration).stream().findFirst();
    }

    private List<Lease> claim(String predicate, Object[] selection, int limit, Duration duration) {
        requireDelay(duration, true);
        return independent.execute(tx -> {
            Object[] args = java.util.Arrays.copyOf(selection, selection.length + 1);
            args[selection.length] = limit;
            List<Long> ids = jdbc.queryForList("SELECT id FROM payment_attempt WHERE status IN ('PROCESSING','UNKNOWN') "
                + "AND scheduled_resolve_at <= " + now + " AND (lease_until IS NULL OR lease_until <= " + now + ")"
                + predicate + " ORDER BY scheduled_resolve_at,id LIMIT ? FOR UPDATE SKIP LOCKED", Long.class, args);
            return ids.stream().map(id -> {
                String token = UUID.randomUUID().toString();
                Instant until = databaseNow().plus(duration);
                jdbc.update("UPDATE payment_attempt SET lease_token=?,lease_until=?,updated_at=" + now + " WHERE id=?",
                    token, Timestamp.from(until), id);
                return jdbc.queryForObject("SELECT id,scenario,retry_count,execution_started_at,lease_token,lease_until "
                    + "FROM payment_attempt WHERE id=?", PaymentRecoveryStore::map, id);
            }).toList();
        });
    }

    public Optional<Integer> beginInvocation(Lease lease) {
        return independent.execute(tx -> {
            int changed = jdbc.update("UPDATE payment_attempt SET retry_count=CASE WHEN execution_started_at IS NULL "
                + "THEN 0 ELSE retry_count+1 END,status=CASE WHEN execution_started_at IS NULL THEN 'PROCESSING' ELSE 'UNKNOWN' END,"
                + "execution_started_at=COALESCE(execution_started_at," + now + "),version=version+1,updated_at=" + now
                + " WHERE " + fence() + " AND (execution_started_at IS NULL OR retry_count<3)", lease.id(), lease.token());
            return changed == 0 ? Optional.empty() : Optional.of(jdbc.queryForObject(
                "SELECT retry_count FROM payment_attempt WHERE id=?", Integer.class, lease.id()));
        });
    }

    public boolean unknown(Lease lease, Duration delay) {
        requireDelay(delay, false);
        return independent.execute(tx -> jdbc.update("UPDATE payment_attempt SET status='UNKNOWN',lease_token=NULL,lease_until=NULL,"
            + "scheduled_resolve_at=?,version=version+1,updated_at=" + now + " WHERE " + fence(),
            Timestamp.from(databaseNow().plus(delay)), lease.id(), lease.token()) == 1);
    }

    public boolean release(Lease lease, Duration delay) {
        requireDelay(delay, false);
        return independent.execute(tx -> jdbc.update("UPDATE payment_attempt SET lease_token=NULL,lease_until=NULL,"
            + "scheduled_resolve_at=?,updated_at=" + now + " WHERE " + fence(),
            Timestamp.from(databaseNow().plus(delay)), lease.id(), lease.token()) == 1);
    }

    /** Participates in the caller's business transaction, and fences again at its final write. */
    public boolean complete(Lease lease, String outcome) {
        if (!List.of("SUCCESS","FAILED").contains(outcome)) throw new IllegalArgumentException("confirmed outcome required");
        return jdbc.update("UPDATE payment_attempt SET status=?,resolved_at=" + now + ",lease_token=NULL,lease_until=NULL,"
            + "version=version+1,updated_at=" + now + " WHERE " + fence(), outcome, lease.id(), lease.token()) == 1;
    }

    public boolean owns(Lease lease) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_attempt WHERE " + fence() + ")",
            Boolean.class, lease.id(), lease.token()));
    }

    private String fence() {
        return "id=? AND lease_token=? AND lease_until>" + now + " AND status IN ('PROCESSING','UNKNOWN') AND resolved_at IS NULL";
    }
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT " + now, Timestamp.class).toInstant();
    }
    private static void requireDelay(Duration d, boolean positive) {
        if (d == null || d.isNegative() || (positive && d.toMillis() < 1))
            throw new IllegalArgumentException("invalid recovery delay");
    }
    private static Lease map(ResultSet rs, int row) throws SQLException {
        Timestamp started = rs.getTimestamp("execution_started_at");
        return new Lease(rs.getLong("id"), PaymentScenario.valueOf(rs.getString("scenario")), rs.getInt("retry_count"),
            started == null ? null : started.toInstant(), rs.getString("lease_token"), rs.getTimestamp("lease_until").toInstant());
    }
    public record Lease(long id, PaymentScenario scenario, int retryCount, Instant startedAt, String token, Instant until) { }
}
