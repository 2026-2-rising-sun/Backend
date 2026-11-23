package com.shoppinglive.commercecore.sku.event;

import com.shoppinglive.commercecore.product.repository.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/** 명시적으로 켠 기동에서만 전체 적재한다. 중단 뒤 재실행도 같은 revision으로 안전하게 반복한다. */
@Component
@ConditionalOnProperty(
        prefix = "commercecore.catalog.backfill",
        name = "enabled",
        havingValue = "true")
public class CatalogSnapshotBackfillRunner implements ApplicationRunner {
    private final ProductRepository products;
    private final CatalogSnapshotBackfill backfill;

    public CatalogSnapshotBackfillRunner(
            ProductRepository products, CatalogSnapshotBackfill backfill) {
        this.products = products;
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        long cursor = 0;
        while (true) {
            var ids = products.findIdsAfter(cursor, Limit.of(100));
            if (ids.isEmpty()) return;
            for (long id : ids) backfill.publishProduct(id);
            cursor = ids.getLast();
        }
    }
}
