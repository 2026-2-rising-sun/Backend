package com.shoppinglive.live.integration.shopping;

import com.shoppinglive.live.integration.ProductLookupException;
import java.util.List;

/**
 * 배치 조회가 유일한 추상 메서드다. 상품별 단건 호출로 N+1 을 만들지 않기 위해
 * 단건 조회도 크기 1 배치로 위임한다. 정상 조회에서 없는 상품은 결과에서 누락된다.
 */
public interface ProductClient {
    List<ProductSnapshot> get(List<Long> productIds);

    default ProductSnapshot get(long productId) {
        return get(List.of(productId)).stream().findFirst()
            .orElseThrow(() -> new ProductLookupException("shopping",
                ProductLookupException.Reason.NOT_FOUND));
    }
}
