package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;

/**
 * {@code shopping.sales.source=replica} 일 때 쓰는 판매정보 조회. Commerce 대신 {@code product_sales} 사본을 읽는다.
 * 사본에 행이 없는 상품은 결과에서 빠진다.
 *
 * <p>DB 읽기 실패는 Commerce 장애와 같은 {@link SalesInfoUnavailableException} 으로 바꾼다. 조회 서비스는
 * {@code SalesInfoClient} 의 이 예외만 알고 있으므로, 상위의 503 과 관리 화면 UNKNOWN 처리가 두 모드에서 같게 동작한다.
 */
@RequiredArgsConstructor
public class ReplicaSalesInfoClient implements SalesInfoClient {

    private final ProductSalesRepository replica;

    @Override
    public Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds) {
        // null 거부만 여기서 한다. 중복 제거와 빈 입력(DB 미접근)은 repository 가 한다.
        ProductIds.distinct(productIds);
        try {
            return replica.findByProductIds(productIds);
        } catch (DataAccessException e) {
            throw new SalesInfoUnavailableException("sales replica read failed", e);
        }
    }
}
