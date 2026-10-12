package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;

/**
 * {@code shopping.sales.source=replica} 일 때 쓰는 판매정보 조회. Commerce 대신 {@code product_sales} 사본을 읽는다.
 *
 * <p>DB 읽기 실패는 Commerce 장애와 같은 {@link SalesInfoUnavailableException} 으로 바꾼다. 그래야 상위의 503 과
 * 관리 화면 UNKNOWN 처리가 두 모드에서 같게 동작한다.
 */
public class ReplicaSalesInfoClient implements SalesInfoClient {

    private final ProductSalesRepository replica;

    public ReplicaSalesInfoClient(ProductSalesRepository replica) {
        this.replica = replica;
    }

    @Override
    public Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds) {
        Set<Long> ids = ProductIds.distinct(productIds);
        // 빈 입력은 DB 를 건드리지 않는다 (계약: 원격·DB 호출 없이 빈 Map).
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return replica.findByProductIds(ids);
        } catch (DataAccessException e) {
            throw new SalesInfoUnavailableException("sales replica read failed", e);
        }
    }
}
