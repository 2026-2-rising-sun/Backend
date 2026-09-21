package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 인메모리 {@link SalesInfoClient}. Commerce 벌크 API 배포 전까지 기본 구현체다.
 * {@link #setUnavailable(boolean)} 로 Commerce 장애를 흉내 내 UNKNOWN 처리 같은 실패 경로를 테스트한다.
 */
@Component
@ConditionalOnProperty(prefix = "shopping.sales-client", name = "mode", havingValue = "stub", matchIfMissing = true)
public class InMemorySalesInfoClientStub implements SalesInfoClient {

    private final Map<Long, SalesInfo> sales = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds) {
        Set<Long> ids = ProductIds.distinct(productIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (unavailable) {
            throw new SalesInfoUnavailableException("sales info stub is set to unavailable");
        }
        Map<Long, SalesInfo> found = new HashMap<>();
        for (Long productId : ids) {
            SalesInfo info = sales.get(productId);
            if (info != null) {
                found.put(productId, info);
            }
        }
        return Map.copyOf(found);
    }

    /** 같은 상품이면 덮어쓴다. */
    public void register(SalesInfo salesInfo) {
        sales.put(salesInfo.productId(), salesInfo);
    }

    public void remove(Long productId) {
        sales.remove(productId);
    }

    /** 데이터와 장애 상태를 모두 초기화한다. 싱글톤이라 테스트마다 호출해 격리한다. */
    public void clear() {
        sales.clear();
        unavailable = false;
    }

    /** {@code true} 면 비어 있지 않은 조회가 {@link SalesInfoUnavailableException} 을 던진다. */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }
}
