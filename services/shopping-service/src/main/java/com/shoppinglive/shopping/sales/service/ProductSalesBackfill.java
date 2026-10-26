package com.shoppinglive.shopping.sales.service;

import com.shoppinglive.shopping.client.commerce.HttpSalesInfoClient;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
import com.shoppinglive.shopping.client.commerce.SalesInfoUnavailableException;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * consume 이 시작되기 전부터 있던 판매정보를 Commerce HTTP 조회로 replica 에 version 0 행으로 채운다.
 *
 * <p>{@code shopping.sales.backfill.enabled=true} 로 기동했을 때만, 애플리케이션 시작 시 한 번 실행한다. 설정이 없으면 이 빈 자체가
 * 만들어지지 않는다. 여러 번 실행해도 없는 행만 채운다.
 *
 * <p>상품 id 를 {@value #CHUNK_SIZE} 개씩 나눠 읽는다. 상품 전체를 한 번에 메모리에 올리지 않고, Commerce 조회 한 번이 받는 id
 * 수(100)에도 맞춘다. Commerce 에 판매정보가 없는 상품은 행을 만들지 않는다.
 *
 * <p>Commerce 조회가 실패하면 그 묶음에서 멈춘다. 앞 묶음까지 저장한 행은 그대로 남고, 실패한 묶음과 그 뒤 상품은 적재되지 않는다. 나머지는 다시 기동해 채운다.
 *
 * <p>구체 타입 {@link HttpSalesInfoClient} 로 주입해 항상 Commerce 를 읽게 한다. replica 를 읽는 SalesInfoClient 가
 * 추가돼도 타입이 달라 주입이 모호해지지 않고, backfill 이 replica 를 원본 삼아 자기 빈을 채우는 일도 없다.
 */
@Component
@ConditionalOnProperty(prefix = "shopping.sales.backfill", name = "enabled", havingValue = "true")
public class ProductSalesBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesBackfill.class);
    private static final int CHUNK_SIZE = 100;

    private final ProductRepository products;
    private final HttpSalesInfoClient commerceClient;
    private final ProductSalesRepository replica;

    public ProductSalesBackfill(
            ProductRepository products,
            HttpSalesInfoClient commerceClient,
            ProductSalesRepository replica) {
        this.products = products;
        this.commerceClient = commerceClient;
        this.replica = replica;
    }

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
                log.error(
                        "판매정보 초기 적재 중단: 조회 상품 수={} 마지막 상품 ID={} 원인={}",
                        scanned,
                        lastId,
                        e.toString());
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
        log.info("판매정보 초기 적재 완료: 조회 상품 수={} 추가 행 수={}", scanned, inserted);
        return inserted;
    }
}
