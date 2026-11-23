package com.shoppinglive.commercecore.sku.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 짧은 행 잠금이 다중 인스턴스 claim이다. Outbox 저장과 완료 표시는 함께 커밋/롤백한다. */
@Component
public class InventorySnapshotBatch {
    private final JdbcTemplate jdbc;
    private final CatalogSnapshotPublisher publisher;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public InventorySnapshotBatch(
            JdbcTemplate jdbc,
            CatalogSnapshotPublisher publisher,
            PlatformTransactionManager manager,
            @Value("${commercecore.catalog.inventory-batch-size:500}") int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("배치 크기는 양수여야 합니다.");
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(manager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${commercecore.catalog.inventory-interval:60s}")
    public int publishChanged() {
        Integer count =
                transactions.execute(
                        status -> {
                            var ids =
                                    jdbc.queryForList(
                                            """
                SELECT id FROM sku WHERE inventory_version>inventory_published_version
                ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                                            Long.class,
                                            batchSize);
                            for (long id : ids) {
                                publisher.inventory(id);
                                jdbc.update(
                                        "UPDATE sku SET inventory_published_version=inventory_version WHERE id=?",
                                        id);
                            }
                            return ids.size();
                        });
        return count == null ? 0 : count;
    }
}
