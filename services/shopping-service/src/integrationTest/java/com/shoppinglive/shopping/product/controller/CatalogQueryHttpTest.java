package com.shoppinglive.shopping.product.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.shopping.client.commerce.HttpSalesInfoClient;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
import com.shoppinglive.shopping.client.commerce.SalesStatus;
import com.shoppinglive.shopping.product.entity.Product;
import com.shoppinglive.shopping.product.entity.ProductCategory;
import com.shoppinglive.shopping.product.entity.ProductImage;
import com.shoppinglive.shopping.product.repository.ProductImageRepository;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import com.shoppinglive.shopping.storage.ImageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:catalog_query;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class CatalogQueryHttpTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired ProductSalesRepository sales;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HttpSalesInfoClient commerce;
    private long imageId;

    @BeforeEach
    void prepare() {
        imageId =
                images.saveAndFlush(
                                new ProductImage(
                                        UUID.randomUUID() + ".png",
                                        ImageFormat.PNG,
                                        100,
                                        10,
                                        10,
                                        "fixture.png"))
                        .getId();
    }

    @AfterEach
    void clean() {
        verifyNoInteractions(commerce);
        jdbc.update("DELETE FROM product_sales");
        products.deleteAll();
        images.deleteAll();
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
    @DisplayName("검색 특수문자는 와일드카드가 아니며 대소문자를 무시한다")
    void wildcardCharactersAreLiteral() throws Exception {
        long match = product("SALE 100%_!", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        product("SALE 100xxx", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        JsonNode data = listing(" sale 100%_! ", "ON_SALE", "LATEST", "");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("productId").asLong()).isEqualTo(match);
    }

    @Test
    @DisplayName("다른 검색·상태·정렬에 커서를 재사용하거나 잘못된 조건을 보내면 입력 오류다")
    void rejectsChangedOrMalformedConditions() throws Exception {
        for (int i = 0; i < 4; i++) product("니트", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
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
    @DisplayName("판매자 상태 필터는 다른 판매자 상품을 제외하고 전체 건수와 페이지를 계산한다")
    void sellerStatusPreservesOwnershipAndTotals() throws Exception {
        for (int i = 0; i < 5; i++)
            product("내 품절", 100, SalesStatus.SOLD_OUT, JwtTestTokens.SELLER);
        product("다른 판매자 품절", 100, SalesStatus.SOLD_OUT, UUID.randomUUID().toString());
        product("내 판매중", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        JsonNode data =
                mapper.readTree(
                                mvc.perform(
                                                get("/v1/seller/products")
                                                        .header("Authorization", sellerBearer())
                                                        .param("status", "SOLD_OUT")
                                                        .param("size", "2")
                                                        .param("page", "2"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        assertThat(data.path("totalElements").asInt()).isEqualTo(5);
        assertThat(data.path("totalPages").asInt()).isEqualTo(3);
        assertThat(data.path("items").size()).isEqualTo(1);
        mvc.perform(get("/v1/seller/products").param("status", "SOLD_OUT"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", userBearer())
                                .param("status", "SOLD_OUT"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", sellerBearer())
                                .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("판매정보 미등록 상태는 사본이 없는 본인 상품만 반환한다")
    void missingSalesFilterUsesOwnProductsOnly() throws Exception {
        long id = product("미등록", 0, null, JwtTestTokens.SELLER);
        product("다른 판매자", 0, null, UUID.randomUUID().toString());
        JsonNode data =
                mapper.readTree(
                                mvc.perform(
                                                get("/v1/seller/products")
                                                        .header("Authorization", sellerBearer())
                                                        .param("status", "NOT_REGISTERED"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("productId").asLong()).isEqualTo(id);
        assertThat(data.path("items").get(0).path("price").isNull()).isTrue();
    }

    @Test
    @DisplayName("검색·상태·정렬 목록은 재고 0과 판매 상태를 보존하고 구매 불가로 응답한다")
    void filteredZeroStockPreservesStatusAndPreventsPurchase() throws Exception {
        for (SalesStatus state : List.of(SalesStatus.ON_SALE, SalesStatus.SOLD_OUT)) {
            long id = product("재고 확인 " + state, 100, state, JwtTestTokens.SELLER);
            sales.apply(new SalesInfo(id, id * 10, 100, state, 0), 2);
            for (String sort : List.of("LATEST", "PRICE_ASC", "PRICE_DESC")) {
                JsonNode items = listing("재고 확인", state.name(), sort, "").path("items");
                assertThat(items.size()).isEqualTo(1);
                JsonNode item = items.get(0);
                assertThat(item.path("productId").asLong()).isEqualTo(id);
                assertThat(item.path("available").isIntegralNumber()).isTrue();
                assertThat(item.path("available").intValue()).isZero();
                assertThat(item.path("salesStatus").asText()).isEqualTo(state.name());
                assertThat(item.path("purchasable").asBoolean()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("조건을 모두 생략한 목록은 sort=LATEST와 같은 페이지와 커서를 반환하고 이전 형식 커서는 거부한다")
    void omittedConditionsMatchLatestAndRejectLegacyCursor() throws Exception {
        for (int i = 0; i < 3; i++)
            product("상품 " + i, 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
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

    private JsonNode listing(String q, String state, String sort, String cursor) throws Exception {
        return mapper.readTree(
                        mvc.perform(
                                        get("/v1/products")
                                                .param("q", q)
                                                .param("status", state)
                                                .param("sort", sort)
                                                .param("cursor", cursor)
                                                .param("size", "2"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("data");
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
                                new Product(
                                        name,
                                        name,
                                        imageId,
                                        UUID.randomUUID().toString(),
                                        sellerId,
                                        category))
                        .getId();
        if (status != null) sales.apply(new SalesInfo(id, id * 10, price, status, 1), 1);
        return id;
    }
}
