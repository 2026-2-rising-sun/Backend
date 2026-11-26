package com.shoppinglive.catalog.product.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.catalog.client.commercecore.HttpSalesInfoClient;
import com.shoppinglive.catalog.client.commercecore.SalesInfo;
import com.shoppinglive.catalog.client.commercecore.SalesStatus;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.catalog.product.repository.ProductRepository;
import com.shoppinglive.catalog.sales.repository.ProductSalesRepository;
import com.shoppinglive.catalog.search.FullIndexer;
import com.shoppinglive.catalog.security.CatalogSecuritySupport;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 공개 목록 API 를 Nori 가 있는 실제 ES 로 확인한다. {@code CATALOG_TEST_ES_URL} 이 가리키는 ES 의 products_* 인덱스를 지우고
 * 쓴다. 색인 주기를 기다리지 않도록 데이터를 넣은 뒤 {@link #indexed()} 로 전체 색인을 돌리고 조회한다.
 */
@Tag("elasticsearch")
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:catalog_query;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "catalog.search.enabled=true",
            "catalog.search.partial.initial-delay=1h",
            "catalog.search.full.initial-delay=1h"
        })
@AutoConfigureMockMvc
class CatalogQueryHttpTest extends CatalogSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductSalesRepository sales;
    @Autowired JdbcTemplate jdbc;
    @Autowired FullIndexer fullIndexer;
    @Autowired ElasticsearchClient client;
    @MockitoBean HttpSalesInfoClient commerce;
    private long imageId;

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> System.getenv("CATALOG_TEST_ES_URL"));
    }

    @BeforeEach
    void prepare() throws IOException {
        imageId = 100L;
        deleteProductIndices();
    }

    @AfterEach
    void clean() throws IOException {
        verifyNoInteractions(commerce);
        jdbc.update("DELETE FROM product_sales");
        products.deleteAll();
        deleteProductIndices();
    }

    /** ES 8 은 와일드카드 삭제를 막으므로 이름을 조회해 지운다. */
    private void deleteProductIndices() throws IOException {
        var names =
                List.copyOf(
                        client.indices()
                                .get(g -> g.index("products_*").allowNoIndices(true))
                                .result()
                                .keySet());
        if (!names.isEmpty()) client.indices().delete(d -> d.index(names));
    }

    /** 지금 Catalog DB 로 검색 인덱스를 다시 만든다. */
    private void indexed() {
        fullIndexer.rebuild();
    }

    @Test
    @DisplayName("전체 상품 검색·상태 필터와 가격 동률 페이지는 누락·중복 없이 이어진다")
    void filtersAndPricePagesCoverAllMatches() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 9; i++)
            expected.add(
                    product("검색 니트 " + i, i / 3 * 100L, SalesStatus.ON_SALE, JwtTestTokens.SELLER));
        product("검색 비공개", 0, SalesStatus.PRIVATE, JwtTestTokens.SELLER);
        product("검색 품절", 0, SalesStatus.SOLD_OUT, JwtTestTokens.SELLER);
        product("다른 상품", 0, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();
        for (String sort : List.of("PRICE_ASC", "PRICE_DESC", "LATEST")) {
            List<Long> actual = new ArrayList<>();
            String cursor = "";
            do {
                JsonNode data = listing("검색", "ON_SALE", sort, cursor);
                data.path("items")
                        .forEach(
                                item -> {
                                    actual.add(item.path("productId").asLong());
                                    assertThat(item.path("available").isIntegralNumber()).isTrue();
                                    assertThat(item.path("available").intValue()).isEqualTo(1);
                                    assertThat(item.path("salesStatus").asText())
                                            .isEqualTo("ON_SALE");
                                    assertThat(item.path("purchasable").asBoolean()).isTrue();
                                });
                cursor = data.path("nextCursor").asText("");
                if (!data.path("hasNext").asBoolean()) break;
                assertThat(cursor).isNotBlank();
            } while (actual.size() < 20);
            if (!sort.equals("PRICE_ASC")) java.util.Collections.reverse(expected);
            assertThat(actual).containsExactlyElementsOf(expected);
            if (!sort.equals("PRICE_ASC")) java.util.Collections.reverse(expected);
        }
    }

    @Test
    @DisplayName("검색어 단어가 모두 들어간 상품만 찾고, 조사와 띄어쓰기가 달라도 찾으며 비슷한 다른 단어는 찾지 않는다")
    void searchesProductNamesByMorphemes() throws Exception {
        long shoes = product("남성 러닝 운동화", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long sportswear = product("남성 운동복 세트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long earphones = product("무선 이어폰 화이트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long serum = product("수분 세럼 50ml", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("럼 레이즌 초콜릿", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("겨울 코트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();

        assertThat(ids(listing("운동화를", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(shoes);
        assertThat(ids(listing("남성 운동화", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(shoes);
        assertThat(ids(listing("운동", "ON_SALE", "LATEST", "").path("items")))
                .containsExactlyInAnyOrder(shoes, sportswear);
        assertThat(ids(listing("무선이어폰", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(earphones);
        assertThat(ids(listing("세럼", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(serum);
        assertThat(ids(listing("남성 코트", "ON_SALE", "LATEST", "").path("items"))).isEmpty();
    }

    @Test
    @DisplayName("동의어 사전의 한글, 영어, 다른 표기로 검색해도 같은 상품을 찾고, 사전 단어의 일부로는 찾지 않는다")
    void searchesSynonyms() throws Exception {
        long shirt = product("남성 옥스포드 셔츠", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long sneakers = product("캔버스 스니커즈", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long jeans = product("와이드 데님 팬츠", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long sunscreen = product("썬크림 SPF50", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long mug = product("흰색 머그컵", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("두루마리 화장지 30롤", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("겨울 코트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();

        assertThat(ids(listing("shirts", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(shirt);
        assertThat(ids(listing("운동화", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(sneakers);
        assertThat(ids(listing("청바지", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(jeans);
        assertThat(ids(listing("선크림", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(sunscreen);
        assertThat(ids(listing("white 머그", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(mug);
        assertThat(ids(listing("화장", "ON_SALE", "LATEST", "").path("items"))).isEmpty();
    }

    @Test
    @DisplayName("검색어의 특수문자는 오류 없이 무시하고 대소문자를 구분하지 않는다")
    void ignoresSymbolsAndCase() throws Exception {
        long match = product("SALE 니트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("코트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();

        assertThat(ids(listing(" sale %_! ", "ON_SALE", "LATEST", "").path("items")))
                .containsExactly(match);
    }

    @Test
    @DisplayName("다른 검색·상태·정렬에 커서를 재사용하거나 잘못된 조건을 보내면 입력 오류다")
    void rejectsChangedOrMalformedConditions() throws Exception {
        for (int i = 0; i < 4; i++) product("니트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();
        String cursor = listing("니트", "ON_SALE", "PRICE_ASC", "").path("nextCursor").asText();
        for (String q : List.of("다른 검색", "x".repeat(101)))
            mvc.perform(
                            get("/v1/products")
                                    .param("q", q)
                                    .param("status", "ON_SALE")
                                    .param("sort", "PRICE_ASC")
                                    .param("cursor", cursor))
                    .andExpect(status().isBadRequest());
        for (String invalid : List.of("PRIVATE", "UNKNOWN", "WRONG"))
            mvc.perform(get("/v1/products").param("status", invalid))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/v1/products")
                                .param("q", "니트")
                                .param("sort", "PRICE_DESC")
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("sort", "LATEST").param("cursor", "broken"))
                .andExpect(status().isBadRequest());
        String excessiveTime =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                mapper.writeValueAsBytes(
                                        java.util.Map.of(
                                                "fingerprint",
                                                "니트\nON_SALE\nPRICE_ASC",
                                                "createdAt",
                                                "+1000000000-12-31T23:59:59.999999999Z",
                                                "id",
                                                1,
                                                "price",
                                                100)));
        mvc.perform(
                        get("/v1/products")
                                .param("q", "니트")
                                .param("status", "ON_SALE")
                                .param("sort", "PRICE_ASC")
                                .param("cursor", excessiveTime))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("sort", "LATEST").param("cursor", "bnVsbA"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("sort", "WRONG")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색·상태·정렬 목록은 재고 0과 판매 상태를 보존하고 Core 확인 진입을 허용한다")
    void filteredZeroStockPreservesStatusAndPreventsPurchase() throws Exception {
        for (SalesStatus state : List.of(SalesStatus.ON_SALE, SalesStatus.SOLD_OUT)) {
            long id = product("재고 확인 " + state, 100, state, JwtTestTokens.SELLER);
            sales.apply(new SalesInfo(id, id * 10, 100, state, 0), 2);
            indexed();
            for (String sort : List.of("LATEST", "PRICE_ASC", "PRICE_DESC")) {
                JsonNode items = listing("재고 확인", state.name(), sort, "").path("items");
                assertThat(items.size()).isEqualTo(1);
                JsonNode item = items.get(0);
                assertThat(item.path("productId").asLong()).isEqualTo(id);
                assertThat(item.path("available").isIntegralNumber()).isTrue();
                assertThat(item.path("available").intValue()).isZero();
                assertThat(item.path("salesStatus").asText()).isEqualTo(state.name());
                assertThat(item.path("purchasable").asBoolean()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("조건을 모두 생략한 목록은 sort=LATEST와 같은 페이지와 커서를 반환하고 이전 형식 커서는 거부한다")
    void omittedConditionsMatchLatestAndRejectLegacyCursor() throws Exception {
        for (int i = 0; i < 3; i++)
            product("상품 " + i, 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();
        String bare = page(get("/v1/products").param("size", "2"));
        assertThat(bare)
                .isEqualTo(page(get("/v1/products").param("sort", "LATEST").param("size", "2")));
        String cursor = mapper.readTree(bare).path("data").path("nextCursor").asText();
        assertThat(page(get("/v1/products").param("cursor", cursor).param("size", "2")))
                .isEqualTo(
                        page(
                                get("/v1/products")
                                        .param("sort", "LATEST")
                                        .param("cursor", cursor)
                                        .param("size", "2")));
        // 삭제한 기존 목록 경로의 커서 형식: Base64URL("<createdAt>_<id>")
        String legacy =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("2026-10-26T00:00:00Z_1".getBytes(UTF_8));
        mvc.perform(get("/v1/products").param("cursor", legacy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("가격 범위는 대표 가격이 양 끝 값을 포함해 범위 안인 상품만 찾고 검색어, 정렬, 커서와 함께 쓴다")
    void filtersByPriceRange() throws Exception {
        long cheap = product("니트 A", 1000, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long low = product("니트 B", 2000, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long mid = product("니트 C", 2500, SalesStatus.SOLD_OUT, JwtTestTokens.SELLER);
        long high = product("니트 D", 3000, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        long expensive = product("니트 E", 4000, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("코트", 2500, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();

        JsonNode first = priced("니트", "2000", "3000", "PRICE_ASC", null);
        assertThat(ids(first.path("items"))).containsExactly(low, mid);
        JsonNode second =
                priced("니트", "2000", "3000", "PRICE_ASC", first.path("nextCursor").asText());
        assertThat(ids(second.path("items"))).containsExactly(high);
        assertThat(second.path("hasNext").asBoolean()).isFalse();

        assertThat(ids(priced("니트", "3000", null, "PRICE_ASC", null).path("items")))
                .containsExactly(high, expensive);
        assertThat(ids(priced("니트", null, "1000", "PRICE_ASC", null).path("items")))
                .containsExactly(cheap);
        assertThat(ids(priced("니트", "2500", "2500", "PRICE_ASC", null).path("items")))
                .containsExactly(mid);

        // 다른 가격 범위나 가격 범위가 없는 조건에 커서를 쓰면 입력 오류다.
        String cursor = first.path("nextCursor").asText();
        for (String[] range : new String[][] {{"2000", "4000"}, {null, null}}) {
            var request =
                    get("/v1/products")
                            .param("q", "니트")
                            .param("sort", "PRICE_ASC")
                            .param("cursor", cursor)
                            .param("size", "2");
            if (range[0] != null) request.param("minPrice", range[0]).param("maxPrice", range[1]);
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("카테고리는 검색·상태·정렬과 함께 적용되고 미분류 상품은 카테고리 필터에서 빠진다")
    void categoryCombinesWithSearchSortAndCursor() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            expected.add(
                    product(
                            "니트 " + i,
                            i / 2 * 100L,
                            SalesStatus.ON_SALE,
                            JwtTestTokens.SELLER,
                            ProductCategory.FASHION));
        product("니트 뷰티", 0, SalesStatus.ON_SALE, JwtTestTokens.SELLER, ProductCategory.BEAUTY);
        product("코트", 0, SalesStatus.ON_SALE, JwtTestTokens.SELLER, ProductCategory.FASHION);
        long unclassified = product("니트 미분류", 0, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        indexed();
        List<Long> actual = new ArrayList<>();
        String cursor = "";
        String firstCursor = null;
        do {
            JsonNode data = mapper.readTree(page(categoryListing("FASHION", cursor))).path("data");
            data.path("items")
                    .forEach(
                            item -> {
                                actual.add(item.path("productId").asLong());
                                assertThat(item.path("category").asText()).isEqualTo("FASHION");
                            });
            if (!data.path("hasNext").asBoolean()) break;
            cursor = data.path("nextCursor").asText();
            if (firstCursor == null) firstCursor = cursor;
        } while (actual.size() < 10);
        assertThat(actual).containsExactlyElementsOf(expected);

        JsonNode all = listing("니트", "ON_SALE", "LATEST", "");
        assertThat(all.path("items").get(0).path("productId").asLong()).isEqualTo(unclassified);
        assertThat(all.path("items").get(0).path("category").isNull()).isTrue();
        mvc.perform(categoryListing("BEAUTY", firstCursor)).andExpect(status().isBadRequest());
        mvc.perform(categoryListing(null, firstCursor)).andExpect(status().isBadRequest());
        for (String invalid : List.of("WRONG", "0"))
            mvc.perform(categoryListing(invalid, ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("복수 SKU 상품은 판매 중 SKU 최저가와 재고 합으로 한 줄이 되고, 모두 품절이면 품절 SKU 기준이다")
    void multipleSkusAreShownByRepresentativeValues() throws Exception {
        long bundle = product("니트 묶음", 300, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        sales.apply(new SalesInfo(bundle, bundle * 10 + 1, 100, SalesStatus.ON_SALE, 2), 1);
        sales.apply(new SalesInfo(bundle, bundle * 10 + 2, 50, SalesStatus.SOLD_OUT, 0), 1);
        sales.apply(new SalesInfo(bundle, bundle * 10 + 3, 10, SalesStatus.PRIVATE, 9), 1);
        List<Long> singles = new ArrayList<>();
        for (int i = 0; i < 2; i++)
            singles.add(product("니트 단일 " + i, 200, SalesStatus.ON_SALE, JwtTestTokens.SELLER));
        indexed();

        JsonNode items = listing("니트", "ON_SALE", "PRICE_ASC", "").path("items");
        assertThat(ids(items)).containsExactly(bundle, singles.get(0));
        assertThat(items.get(0).path("price").asLong()).isEqualTo(100);
        assertThat(items.get(0).path("available").asInt()).isEqualTo(3);
        assertThat(items.get(0).path("salesStatus").asText()).isEqualTo("ON_SALE");

        sales.apply(new SalesInfo(bundle, bundle * 10, 300, SalesStatus.SOLD_OUT, 0), 2);
        sales.apply(new SalesInfo(bundle, bundle * 10 + 1, 100, SalesStatus.SOLD_OUT, 1), 2);
        indexed();

        assertThat(ids(listing("니트", "ON_SALE", "PRICE_ASC", "").path("items")))
                .containsExactlyElementsOf(singles);
        JsonNode soldOut = listing("니트", "SOLD_OUT", "PRICE_ASC", "").path("items");
        assertThat(ids(soldOut)).containsExactly(bundle);
        assertThat(soldOut.get(0).path("price").asLong()).isEqualTo(50);
        assertThat(soldOut.get(0).path("available").asInt()).isEqualTo(1);
        // 상세는 여전히 단일 판매정보만 응답한다.
        mvc.perform(get("/v1/products/" + bundle)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("비공개, 미등록, 삭제 상품은 목록에서 빠지고 판매 중지 상품은 구매만 막는다")
    void hiddenProductsAreExcludedAndPausedProductCannotBePurchased() throws Exception {
        product("숨김 상품", 100, SalesStatus.PRIVATE, JwtTestTokens.SELLER);
        product("미등록 상품", 100, null, JwtTestTokens.SELLER);
        long paused = product("중지 상품", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        jdbc.update("UPDATE product SET sale_enabled=FALSE WHERE id=?", paused);
        indexed();

        JsonNode items = listing("상품", null, "LATEST", "").path("items");
        assertThat(ids(items)).containsExactly(paused);
        assertThat(items.get(0).path("salesStatus").asText()).isEqualTo("ON_SALE");
        assertThat(items.get(0).path("purchasable").asBoolean()).isFalse();

        // 색인이 반영되기 전에 삭제돼도 응답 때 DB 기준으로 뺀다.
        jdbc.update("UPDATE product SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", paused);
        JsonNode stale = listing("상품", null, "LATEST", "");
        assertThat(stale.path("items")).isEmpty();
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("productId").asLong()));
        return ids;
    }

    private MockHttpServletRequestBuilder categoryListing(String category, String cursor) {
        MockHttpServletRequestBuilder request =
                get("/v1/products")
                        .param("q", "니트")
                        .param("status", "ON_SALE")
                        .param("sort", "PRICE_ASC")
                        .param("cursor", cursor)
                        .param("size", "2");
        return category == null ? request : request.param("category", category);
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode priced(String q, String min, String max, String sort, String cursor)
            throws Exception {
        var request =
                get("/v1/products")
                        .param("q", q)
                        .param("sort", sort)
                        .param("cursor", cursor == null ? "" : cursor)
                        .param("size", "2");
        if (min != null) request.param("minPrice", min);
        if (max != null) request.param("maxPrice", max);
        return mapper.readTree(
                        mvc.perform(request)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("data");
    }

    private JsonNode listing(String q, String state, String sort, String cursor) throws Exception {
        return mapper.readTree(
                        mvc.perform(
                                        get("/v1/products")
                                                .param("q", q)
                                                .param("status", state == null ? "" : state)
                                                .param("sort", sort)
                                                .param("cursor", cursor)
                                                .param("size", "2"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("data");
    }

    @Test
    @DisplayName("통합 다중 SKU는 첫 SKU만 도착해도 상세에서 단일 상품으로 노출되지 않고 이벤트 역순에도 유지된다")
    void rejectsPartialBundleReplica() throws Exception {
        long id = product("통합 상품", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        var snapshots =
                new com.shoppinglive.catalog.product.repository.ProductReplicaRepository(
                        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                                jdbc));
        var time = java.time.Instant.now();
        snapshots.apply(
                new com.shoppinglive.catalog.product.event.ProductInfoEventMessage(
                        id,
                        "통합 상품",
                        "설명",
                        null,
                        1,
                        "/image",
                        JwtTestTokens.SELLER,
                        10,
                        time,
                        time,
                        true,
                        null,
                        2));
        for (int stage = 0; stage < 2; stage++) {
            mvc.perform(get("/v1/products/" + id)).andExpect(status().isServiceUnavailable());
            sales.apply(new SalesInfo(id, id * 10 + 1, 200, SalesStatus.ON_SALE, 2), 1);
        }
        snapshots.apply(
                new com.shoppinglive.catalog.product.event.ProductInfoEventMessage(
                        id,
                        "과거 상품",
                        "설명",
                        null,
                        1,
                        "/image",
                        JwtTestTokens.SELLER,
                        0,
                        time,
                        time,
                        true,
                        null));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initial_sku_count FROM product WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(2);
    }

    private long product(String name, long price, SalesStatus status, String sellerId) {
        return product(name, price, status, sellerId, null);
    }

    private long product(
            String name,
            long price,
            SalesStatus status,
            String sellerId,
            ProductCategory category) {
        long id =
                products.saveAndFlush(
                                com.shoppinglive.catalog.product.support.ProductReplicaFixtures
                                        .product(name, name, imageId, sellerId, category))
                        .getId();
        if (status != null) sales.apply(new SalesInfo(id, id * 10, price, status, 1), 1);
        return id;
    }
}
