package com.shoppinglive.live.integration.commerce;

import com.shoppinglive.live.integration.ProductLookupException;
import java.util.List;

/** ProductClient 와 같은 이유로 배치가 유일한 추상 메서드다. */
public interface SalesClient {
    List<SalesSnapshot> get(List<Long> productIds);

    default SalesSnapshot get(long productId) {
        return get(List.of(productId)).stream().findFirst()
            .orElseThrow(() -> new ProductLookupException("commerce",
                ProductLookupException.Reason.NOT_FOUND));
    }
}
