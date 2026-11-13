package com.shoppinglive.commercecore.sku.event;

import com.shoppinglive.commercecore.sales.event.SalesInfoEventContract;
import com.shoppinglive.commercecore.sales.event.SalesInfoEventType;
import com.shoppinglive.commercecore.sales.event.SalesInfoSnapshot;
import com.shoppinglive.commercecore.sales.event.SalesInfoStatus;
import com.shoppinglive.commercecore.sales.exception.SalesNotFoundException;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SKU의 최종 표시 스냅샷과 업무 변경을 같은 트랜잭션에 기록한다. */
@Component
public class SkuEventTracker {
    private final JdbcTemplate jdbc;
    private final EventOutbox outbox;

    public SkuEventTracker(JdbcTemplate jdbc, EventOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void watch(long skuId) {
        Pending pending = pending();
        if (pending.before.containsKey(skuId)) return;
        if (jdbc.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE", Long.class, skuId)
                .isEmpty()) {
            throw new SalesNotFoundException("SKU를 찾을 수 없습니다: skuId=" + skuId);
        }
        pending.before.put(skuId, snapshot(skuId));
    }

    public void registered(long skuId) {
        pending().before.put(skuId, null);
    }

    private Pending pending() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("SKU 변경에는 쓰기 가능한 활성 트랜잭션이 필요합니다.");
        }
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
                SELECT id,product_id,snapshot_version,price,available,product_name_snapshot,
                    CASE WHEN sale_status='ON_SALE' AND available=0 THEN 'SOLD_OUT'
                         ELSE sale_status END AS display_status
                FROM sku WHERE id=?
                """,
                (rs, row) ->
                        new SalesInfoSnapshot(
                                rs.getLong("id"),
                                rs.getLong("product_id"),
                                rs.getLong("snapshot_version"),
                                rs.getLong("price"),
                                SalesInfoStatus.valueOf(rs.getString("display_status")),
                                rs.getInt("available"),
                                "KRW",
                                rs.getString("product_name_snapshot")),
                id);
    }

    private final class Pending implements TransactionSynchronization {
        private final Map<Long, SalesInfoSnapshot> before = new LinkedHashMap<>();

        private SkuEventTracker owner() {
            return SkuEventTracker.this;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
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
                                    "UPDATE sku SET snapshot_version=snapshot_version+1 WHERE id=?",
                                    entry.getKey())
                            != 1) throw new IllegalStateException("SKU 스냅샷이 사라졌습니다.");
                    current = snapshot(entry.getKey());
                }
                Instant occurredAt = Instant.now();
                for (SalesInfoEventType type : types) {
                    outbox.append(
                            SalesInfoEventContract.TOPIC_NAME,
                            SalesInfoEventContract.create(type, occurredAt, current));
                }
            }
        }
    }
}
