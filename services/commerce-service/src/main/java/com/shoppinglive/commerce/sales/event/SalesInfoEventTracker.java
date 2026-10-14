package com.shoppinglive.commerce.sales.event;

import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.common.events.EventEnvelope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Collects final per-sale snapshots inside the owning transaction, then sends only after commit.
 */
@Component
public class SalesInfoEventTracker {
    private final JdbcTemplate jdbc;
    private final SalesInfoEventProducer producer;
    @PersistenceContext private EntityManager entityManager;

    public SalesInfoEventTracker(JdbcTemplate jdbc, SalesInfoEventProducer producer) {
        this.jdbc = jdbc;
        this.producer = producer;
    }

    /** Must precede every price/status/stock mutation. Matches the existing purchase lock order. */
    public void watch(long salesId) {
        Pending pending = pending();
        if (pending.before.containsKey(salesId)) return;
        if (jdbc.queryForList(
                        "SELECT sales_info_id FROM sales_stock WHERE sales_info_id=? FOR UPDATE",
                        Long.class,
                        salesId)
                .isEmpty())
            throw new SalesNotFoundException("sales stock not found: id=" + salesId);
        if (jdbc.queryForList(
                        "SELECT id FROM sales_info WHERE id=? FOR UPDATE", Long.class, salesId)
                .isEmpty()) {
            throw new SalesNotFoundException("sales not found: id=" + salesId);
        }
        pending.before.put(salesId, snapshot(salesId));
    }

    /** Called after inserting both sales_info and sales_stock in the registration transaction. */
    public void registered(long salesId) {
        pending().before.put(salesId, null);
    }

    private Pending pending() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Sales events require an active writable transaction");
        }
        // Synchronizations are suspended with REQUIRES_NEW, so collection never leaks between
        // transactions.
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending && pending.owner() == this)
                return pending;
        }
        Pending pending = new Pending();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private SalesInfoSnapshot snapshot(long id) {
        return jdbc.queryForObject(
                """
            SELECT s.id,s.product_id,s.snapshot_version,s.price,s.status,t.available
            FROM sales_info s JOIN sales_stock t ON t.sales_info_id=s.id WHERE s.id=?
            """,
                (rs, row) ->
                        new SalesInfoSnapshot(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                SalesInfoStatus.valueOf(rs.getString(5)),
                                rs.getInt(6),
                                "KRW"),
                id);
    }

    private final class Pending implements TransactionSynchronization {
        private final Map<Long, SalesInfoSnapshot> before = new LinkedHashMap<>();
        private final List<EventEnvelope<SalesInfoSnapshot>> events = new ArrayList<>();

        private SalesInfoEventTracker owner() {
            return SalesInfoEventTracker.this;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            entityManager.flush();
            for (Map.Entry<Long, SalesInfoSnapshot> entry : before.entrySet()) {
                SalesInfoSnapshot initial = entry.getValue();
                SalesInfoSnapshot current = snapshot(entry.getKey());
                List<SalesInfoEventType> types = new ArrayList<>();
                if (initial == null) types.add(SalesInfoEventType.SalesRegistered);
                else {
                    if (initial.price() != current.price())
                        types.add(SalesInfoEventType.PriceChanged);
                    if (initial.available() != current.available())
                        types.add(SalesInfoEventType.InventoryChanged);
                    if (initial.status() != current.status())
                        types.add(SalesInfoEventType.SalesStatusChanged);
                }
                if (types.isEmpty()) continue;
                if (initial != null) {
                    if (jdbc.update(
                                    "UPDATE sales_info SET snapshot_version=snapshot_version+1 WHERE id=?",
                                    entry.getKey())
                            != 1) throw new IllegalStateException("Sales snapshot disappeared");
                    current = snapshot(entry.getKey());
                }
                Instant occurredAt = Instant.now();
                for (SalesInfoEventType type : types)
                    events.add(SalesInfoEventContract.create(type, occurredAt, current));
            }
        }

        @Override
        public void afterCommit() {
            events.forEach(producer::send);
        }
    }
}
