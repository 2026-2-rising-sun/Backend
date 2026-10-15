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

/** 업무 트랜잭션 안에서 판매별 최종 스냅샷을 수집하고 커밋 후에만 발행한다. */
@Component
public class SalesInfoEventTracker {
    private final JdbcTemplate jdbc;
    private final SalesInfoEventProducer producer;
    @PersistenceContext private EntityManager entityManager;

    public SalesInfoEventTracker(JdbcTemplate jdbc, SalesInfoEventProducer producer) {
        this.jdbc = jdbc;
        this.producer = producer;
    }

    /** 기존 구매 잠금 순서를 유지하도록 가격·상태·재고 변경 전에 호출한다. */
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

    /** 등록 트랜잭션에서 판매정보와 재고를 모두 저장한 뒤 호출한다. */
    public void registered(long salesId) {
        pending().before.put(salesId, null);
    }

    private Pending pending() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("판매 이벤트 수집에는 쓰기 가능한 활성 트랜잭션이 필요합니다.");
        }
        // REQUIRES_NEW에서 기존 동기화가 중단되므로 트랜잭션별 수집 상태가 섞이지 않는다.
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
                            != 1) throw new IllegalStateException("판매정보 스냅샷이 사라졌습니다.");
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
