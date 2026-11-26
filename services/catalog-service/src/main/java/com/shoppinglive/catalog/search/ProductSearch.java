package com.shoppinglive.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.shoppinglive.catalog.client.commercecore.SalesDisplayStatus;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.catalog.product.service.ProductSort;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 공개 목록 조건에 맞는 상품 id 를 ES 에서 정렬된 순서로 찾는다. 가격, 재고, 판매 상태는 응답 때 Catalog DB 에서 붙인다.
 *
 * <p>상품명은 검색어를 Nori 로 나눈 단어가 모두 들어간 상품을 찾는다. 색인과 검색어 모두 합성어를 부분으로 나누고 잘못 나뉘는 쇼핑 단어는 사용자 사전으로
 * 고정해서(products.json), "무선이어폰" 과 "무선 이어폰" 이 서로 찾히고 "운동화" 로 운동복은 찾히지 않는다. 정렬의 마지막 기준은 항상 productId
 * 라서 같은 가격이나 같은 시각이어도 순서가 정해지고, 다음 페이지는 직전 페이지 마지막 상품의 정렬 값 뒤부터 이어진다(search_after). ES 가 응답하지 않거나
 * alias 가 아직 없으면 503 이다.
 */
@Component
public class ProductSearch {
    private final ElasticsearchClient client;

    public ProductSearch(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * @param keyword 앞뒤 공백을 뺀 검색어. 비어 있으면 상품명 조건을 걸지 않는다
     * @param after 직전 페이지 마지막 상품의 정렬 값. 첫 페이지면 null
     */
    public Page find(
            String keyword,
            SalesDisplayStatus status,
            ProductCategory category,
            ProductSort sort,
            List<Long> after,
            int size) {
        SearchRequest.Builder request =
                new SearchRequest.Builder()
                        .index(ElasticsearchProductIndex.ALIAS)
                        // 다음 페이지가 있는지 보려고 하나 더 읽는다.
                        .size(size + 1)
                        .source(s -> s.fetch(false))
                        .trackTotalHits(t -> t.enabled(false))
                        .query(query(keyword, status, category))
                        .sort(order(sort));
        if (after != null) request.searchAfter(after.stream().map(FieldValue::of).toList());
        try {
            List<Hit<Map>> hits = client.search(request.build(), Map.class).hits().hits();
            List<Hit<Map>> page = hits.subList(0, Math.min(size, hits.size()));
            List<Long> ids = new ArrayList<>();
            for (Hit<Map> hit : page) ids.add(Long.parseLong(hit.id()));
            List<Long> lastSort =
                    page.isEmpty()
                            ? null
                            : page.getLast().sort().stream().map(FieldValue::longValue).toList();
            return new Page(ids, lastSort, hits.size() > size);
        } catch (IOException | ElasticsearchException failure) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "상품 검색을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static Query query(
            String keyword, SalesDisplayStatus status, ProductCategory category) {
        List<FieldValue> statuses =
                status == null
                        ? List.of(FieldValue.of("ON_SALE"), FieldValue.of("SOLD_OUT"))
                        : List.of(FieldValue.of(status.name()));
        BoolQuery.Builder bool =
                new BoolQuery.Builder()
                        .filter(
                                TermsQuery.of(t -> t.field("status").terms(v -> v.value(statuses)))
                                        ._toQuery());
        if (category != null)
            bool.filter(TermQuery.of(t -> t.field("category").value(category.name()))._toQuery());
        if (!keyword.isEmpty())
            bool.must(
                    MatchQuery.of(m -> m.field("name").query(keyword).operator(Operator.And))
                            ._toQuery());
        return bool.build()._toQuery();
    }

    private static List<SortOptions> order(ProductSort sort) {
        String first = sort == ProductSort.LATEST ? "createdAt" : "price";
        SortOrder direction = sort == ProductSort.PRICE_ASC ? SortOrder.Asc : SortOrder.Desc;
        return List.of(
                SortOptions.of(o -> o.field(f -> f.field(first).order(direction))),
                SortOptions.of(o -> o.field(f -> f.field("productId").order(direction))));
    }

    /**
     * @param lastSort 이 페이지 마지막 상품의 정렬 값. 비어 있는 페이지면 null
     */
    public record Page(List<Long> productIds, List<Long> lastSort, boolean hasNext) {}
}
