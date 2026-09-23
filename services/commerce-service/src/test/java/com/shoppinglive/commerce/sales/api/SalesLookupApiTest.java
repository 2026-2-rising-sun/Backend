package com.shoppinglive.commerce.sales.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.sales.application.SalesLookupService;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매정보 배치 조회 API 의 HTTP 계약 검증.
 *
 * <p>여기서 지키는 것은 <b>Shopping·Live 와 합의한 응답 모양</b>이다. 판정 로직은
 * {@code SalesLookupServiceTest} 가 덮으므로, 이 테스트는 서비스 계층에서 확인할 수 없는 것만
 * 본다 — 파라미터 형태, 봉투 없는 배열, 필드 이름, 상태코드.
 *
 * <p>필드 이름이 바뀌면 Shopping {@code CommerceSalesResponse} 와 Live {@code SalesSnapshot}
 * 이 동시에 깨지는데, 두 서비스는 별도 모듈이라 컴파일러가 잡아주지 않는다. 그래서 이름을
 * {@code jsonPath} 로 하나씩 박아 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesLookupApiTest {

    private static final long FIRST_PRODUCT = 9_201L;
    private static final long SECOND_PRODUCT = 9_202L;
    private static final long UNREGISTERED_PRODUCT = 9_299L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

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
        return mockMvc.perform(get("/v1/sales" + query));
    }

    /**
     * Shopping {@code HttpSalesInfoClient} 가 보내는 형태. 쉼표로 이어 붙인 하나의 파라미터다.
     */
    @Test
    void 쉼표로_이어_보내면_모두_조회된다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "," + SECOND_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * Live {@code HttpSalesClient} 가 보내는 형태. 같은 이름의 파라미터를 여러 번 보낸다.
     * 두 서비스가 서로 다른 방식으로 보내므로 한쪽만 받으면 나머지 한쪽이 못 쓴다.
     */
    @Test
    void 파라미터를_여러_번_보내도_모두_조회된다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "&productIds=" + SECOND_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * 부르는 두 서비스 모두 {@code List<...>} 로 바로 읽는다. {@code ApiResponse} 로 감싸면
     * {@code $.data} 아래로 내려가 양쪽이 동시에 깨진다.
     */
    @Test
    void 응답은_봉투_없는_배열이다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 합의한_다섯_필드를_그대로_내보낸다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].productId").value(FIRST_PRODUCT))
            .andExpect(jsonPath("$[0].salesId").isNumber())
            .andExpect(jsonPath("$[0].price").value(15_000))
            .andExpect(jsonPath("$[0].status").value("ON_SALE"))
            .andExpect(jsonPath("$[0].available").value(7));
    }

    /** 낙관적 락 버전 같은 내부 값은 계약에 없다. 한 번 내보내면 나중에 빼기 어렵다. */
    @Test
    void 내부_전용_필드는_내보내지_않는다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].version").doesNotExist())
            .andExpect(jsonPath("$[0].reserved").doesNotExist());
    }

    /** 품절도 정상 응답이다. 화면에 "품절" 로 표시해야 하므로 빼면 안 된다. */
    @Test
    void 품절_상품도_상태와_함께_내려간다() throws Exception {
        lookup("?productIds=" + SECOND_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("SOLD_OUT"))
            .andExpect(jsonPath("$[0].available").value(0));
    }

    @Test
    void 미등록_상품은_배열에서_빠지고_나머지는_정상_응답한다() throws Exception {
        lookup("?productIds=" + FIRST_PRODUCT + "," + UNREGISTERED_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].productId").value(FIRST_PRODUCT));
    }

    @Test
    void 전부_미등록이면_빈_배열_200이다() throws Exception {
        lookup("?productIds=" + UNREGISTERED_PRODUCT)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 파라미터가_없으면_400이다() throws Exception {
        lookup("").andExpect(status().isBadRequest());
    }

    @Test
    void 숫자가_아니면_400이다() throws Exception {
        lookup("?productIds=abc").andExpect(status().isBadRequest());
    }

    @Test
    void 상한을_넘으면_400이다() throws Exception {
        String tooMany = LongStream.rangeClosed(1L, SalesLookupService.MAX_PRODUCT_IDS + 1)
            .mapToObj(String::valueOf)
            .collect(Collectors.joining(","));

        lookup("?productIds=" + tooMany).andExpect(status().isBadRequest());
    }

    /** 400 도 공통 봉투를 쓴다. 성공 응답만 계약상 봉투가 없다. */
    @Test
    void 잘못된_요청은_공통_오류_형식으로_내려간다() throws Exception {
        lookup("?productIds=abc")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
