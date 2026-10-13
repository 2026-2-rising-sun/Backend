package com.shoppinglive.shopping.product.infrastructure;

import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.infrastructure.HttpSalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.ProductSalesRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * consume 이 시작되기 전부터 있던 판매정보를 Commerce HTTP 조회로 replica 에 version 0 행으로 채운다.
 *
 * <p>구체 타입 {@link HttpSalesInfoClient} 로 주입해 항상 Commerce 를 읽게 한다. replica 를 읽는 SalesInfoClient 가
 * 추가돼도 타입이 달라 주입이 모호해지지 않고, backfill 이 replica 를 원본 삼아 자기 빈을 채우는 일도 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shopping.sales.backfill", name = "enabled", havingValue = "true")
public class ProductSalesBackfill implements ApplicationRunner {

    private static final int CHUNK_SIZE = 100;

    private final ProductRepository products;
    private final HttpSalesInfoClient commerceClient;
    private final ProductSalesRepository replica;

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    // shortcut: 기동 중 동기 실행이라 상품 수에 비례해 startup 이 길어진다, 상품이 많아져 기동이 느려지면 별도 job 으로 옮긴다
    /**
     * @return 새로 저장한 행 수. 조회 실패로 멈추면 그 전까지 저장한 행 수다.
     */
    int backfill() {
        int scanned = 0;
        int inserted = 0;
        long lastId = 0L;
        List<Long> ids = products.findIdsAfter(lastId, Limit.of(CHUNK_SIZE));
        while (!ids.isEmpty()) {
            Map<Long, SalesInfo> found;
            try {
                found = commerceClient.findByProductIds(ids);
            } catch (SalesInfoUnavailableException e) {
                // 다시 실행하면 없는 행만 채우므로 재시도하지 않고 여기서 끝낸다. 앱은 계속 뜬다.
                log.error("sales backfill stopped: scannedProducts={} lastProductId={} cause={}",
                    scanned, lastId, e.toString());
                return inserted;
            }
            for (SalesInfo info : found.values()) {
                if (replica.insertIfAbsent(info)) {
                    inserted++;
                }
            }
            scanned += ids.size();
            lastId = ids.getLast();
            ids = products.findIdsAfter(lastId, Limit.of(CHUNK_SIZE));
        }
        log.info("sales backfill finished: scannedProducts={} insertedRows={}", scanned, inserted);
        return inserted;
    }
}
