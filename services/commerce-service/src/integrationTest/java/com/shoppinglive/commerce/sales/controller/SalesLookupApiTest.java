package com.shoppinglive.commerce.sales.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commerce.sales.service.SalesLookupService;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매정보 배치 조회 API 의 HTTP 계약 검증.
 *
 * <p>여기서 지키는 것은 <b>Shopping·Live 와 합의한 응답 모양</b>이다. 판정 로직은 {@code SalesLookupServiceTest} 가 덮으므로, 이
 * 테스트는 서비스 계층에서 확인할 수 없는 것만 본다 — 파라미터 형태, 봉투 없는 배열, 필드 이름, 상태코드.
 *
 * <p>필드 이름이 바뀌면 Shopping {@code CommerceSalesResponse} 와 Live {@code SalesSnapshot} 이 동시에 깨지는데, 두
 * 서비스는 별도 모듈이라 컴파일러가 잡아주지 않는다. 그래서 이름을 {@code jsonPath} 로 하나씩 박아 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesLookupApiTest extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

    private static final long FIRST_PRODUCT = 9_201L;
    private static final long SECOND_PRODUCT = 9_202L;
    private static final long UNREGISTERED_PRODUCT = 9_299L;

    @Autowired private MockMvc mockMvc;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    @BeforeEach
    void setUp() {
        clean();
        register(FIRST_PRODUCT, 15_000L, SalesStatus.ON_SALE, 7, 2);
        register(SECOND_PRODUCT, 20_000L, SalesStatus.SOLD_OUT, 0, 0);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    private void register(
            long productId, long price, SalesStatus status, int available, int reserved) {
        Sales sales = salesRepository.save(new Sales(productId, price, status));
        salesStockRepository.save(new SalesStock(sales.getId(), available, reserved));
    }

    private ResultActions lookup(String query) throws Exception {
        return mockMvc.perform(get("/v1/sales" + query).header("X-Service-Token", SHOPPING_TOKEN));
    }

    /** Shopping {@code HttpSalesInfoClient} 가 보내는 형태. 쉼표로 이어 붙인 하나의 파라미터다. */
    @DisplayName("쉼표로 구분한 상품 ID를 모두 조회한다")
    @Test
    void returnsAllSalesForCommaSeparatedProductIds() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "," + SECOND_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * Live {@code HttpSalesClient} 가 보내는 형태. 같은 이름의 파라미터를 여러 번 보낸다. 두 서비스가 서로 다른 방식으로 보내므로 한쪽만 받으면
     * 나머지 한쪽이 못 쓴다.
     */
    @DisplayName("파라미터를 반복 지정한 상품 ID도 모두 조회한다")
    @Test
    void returnsAllSalesForRepeatedProductIdParameters() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "&productIds=" + SECOND_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * 부르는 두 서비스 모두 {@code List<...>} 로 바로 읽는다. {@code ApiResponse} 로 감싸면 {@code $.data} 아래로 내려가 양쪽이
     * 동시에 깨진다.
     */
    @DisplayName("판매 일괄 조회는 공통 봉투 없이 배열로 응답한다")
    @Test
    void returnsArrayWithoutResponseEnvelope() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @DisplayName("판매 일괄 조회는 계약의 다섯 필드를 그대로 반환한다")
    @Test
    void returnsExactlyFiveContractFields() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(FIRST_PRODUCT))
                .andExpect(jsonPath("$[0].salesId").isNumber())
                .andExpect(jsonPath("$[0].price").value(15_000))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$[0].available").value(7));
    }

    /** 낙관적 락 버전 같은 내부 값은 계약에 없다. 한 번 내보내면 나중에 빼기 어렵다. */
    @DisplayName("판매 일괄 조회에 내부 전용 필드를 노출하지 않는다")
    @Test
    void omitsInternalOnlyFields() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").doesNotExist())
                .andExpect(jsonPath("$[0].reserved").doesNotExist());
    }

    /** 품절도 정상 응답이다. 화면에 "품절" 로 표시해야 하므로 빼면 안 된다. */
    @DisplayName("품절 상품도 판매 상태와 함께 반환한다")
    @Test
    void returnsSoldOutProductWithItsStatus() throws Exception {
        lookup("?productIds=" + SECOND_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SOLD_OUT"))
                .andExpect(jsonPath("$[0].available").value(0));
    }

    @DisplayName("미등록 상품만 제외하고 나머지 상품은 정상 반환한다")
    @Test
    void omitsMissingProductsAndReturnsRemainingSales() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "," + UNREGISTERED_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(FIRST_PRODUCT));
    }

    @DisplayName("모든 상품이 미등록이면 200과 빈 배열을 반환한다")
    @Test
    void returns200AndEmptyArrayWhenAllProductsAreMissing() throws Exception {
        lookup("?productIds=" + UNREGISTERED_PRODUCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 무엇을 조회할지 정하지 않은 요청이다. 전체를 내보낼지 아무것도 안 내보낼지 서버가 정할 수 없으므로 거절한다. */
    @DisplayName("상품 ID 파라미터가 없으면 400으로 응답한다")
    @Test
    void returns400WhenProductIdsParameterIsMissing() throws Exception {
        lookup("").andExpect(status().isBadRequest());
    }

    /**
     * 파라미터는 왔는데 값이 비었으면 400 이 아니라 빈 배열이다. 물어본 게 없으니 답도 없는 것이지 잘못된 요청은 아니고, 미등록 상품을 물었을 때와 같은 모양으로
     * 나가야 부르는 쪽이 한 가지 경우만 다루면 된다.
     */
    @DisplayName("상품 ID 파라미터 값이 비어 있으면 200과 빈 배열을 반환한다")
    @Test
    void returns200AndEmptyArrayWhenProductIdsValueIsEmpty() throws Exception {
        lookup("?productIds=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @DisplayName("상품 ID가 숫자가 아니면 400으로 응답한다")
    @Test
    void returns400ForNonNumericProductIds() throws Exception {
        lookup("?productIds=abc").andExpect(status().isBadRequest());
    }

    @DisplayName("상품 ID 개수가 상한을 넘으면 400으로 응답한다")
    @Test
    void returns400WhenProductIdCountExceedsLimit() throws Exception {
        String tooMany =
                LongStream.rangeClosed(1L, SalesLookupService.MAX_PRODUCT_IDS + 1)
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(","));

        lookup("?productIds=" + tooMany).andExpect(status().isBadRequest());
    }

    /** 400 도 공통 봉투를 쓴다. 성공 응답만 계약상 봉투가 없다. */
    @DisplayName("잘못된 판매 조회 요청은 공통 오류 형식으로 응답한다")
    @Test
    void returnsCommonErrorEnvelopeForInvalidRequest() throws Exception {
        lookup("?productIds=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
