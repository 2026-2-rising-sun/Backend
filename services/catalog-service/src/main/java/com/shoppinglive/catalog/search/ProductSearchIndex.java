package com.shoppinglive.catalog.search;

import java.util.Collection;
import java.util.Set;

/** 검색 색인 저장소. 부분 색인은 이 경계로 ES 를 쓴다. */
public interface ProductSearchIndex {

    /**
     * 문서를 넣거나 고치고, 공개 대상이 아닌 상품 문서를 지운다. 없는 문서 삭제는 성공으로 본다.
     *
     * @return 반영하지 못한 상품 id. 다음 주기에 다시 처리한다
     */
    Set<Long> write(Collection<ProductSearchDocument> upserts, Collection<Long> deletes);
}
