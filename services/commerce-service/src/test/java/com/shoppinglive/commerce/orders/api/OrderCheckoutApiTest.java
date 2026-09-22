package com.shoppinglive.commerce.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 주문 1 주문서 확인 API 의 HTTP 계약 검증.
 *
 * <p>서비스 단위 테스트가 이미 판정 규칙을 덮으므로, 여기서는 상태코드와 라우팅처럼 서비스
 * 계층에서 확인할 수 없는 것만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCheckoutApiTest {

    private static final long PRODUCT_ID = 700L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryShoppingClientStub shoppingClientStub;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @BeforeEach
    void setUp() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
        shoppingClientStub.register(new ProductSnapshot(PRODUCT_ID, "테스트 상품", null));

        Sales sales = salesRepository.save(new Sales(PRODUCT_ID, 15_000L, SalesStatus.ON_SALE));
        salesStockRepository.save(new SalesStock(sales.getId(), 5, 0));
    }

    @AfterEach
    void tearDown() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
    }

    private ResultActions checkout(String query) throws Exception {
        return mockMvc.perform(get("/v1/orders/checkout" + query));
    }

    @Test
    void 주문_가능하면_200과_총액을_돌려준다() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=2")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.productName").value("테스트 상품"))
            .andExpect(jsonPath("$.unitPrice").value(15_000))
            .andExpect(jsonPath("$.totalAmount").value(30_000))
            .andExpect(jsonPath("$.orderable").value(true))
            .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void 재고보다_많으면_200이면서_사유를_준다() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=6")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderable").value(false))
            .andExpect(jsonPath("$.reason").value("EXCEEDS_STOCK"))
            .andExpect(jsonPath("$.available").value(5));
    }

    /**
     * 주문 1 완료 기준: "주문서 확인 중에는 재고가 줄지 않고, 실제 주문 생성 때만 재고를
     * 확보한다". 주문서를 여러 번 열어봐도 DB 재고가 그대로여야 한다. 그렇지 않으면 결제까지
     * 가지 않고 이탈한 사람들 때문에 팔 수 있는 물건이 묶인다.
     */
    @Test
    void 주문서를_여러번_열어도_재고가_줄지_않는다() throws Exception {
        Long salesId = salesRepository.findByProductId(PRODUCT_ID).orElseThrow().getId();

        for (int i = 0; i < 3; i++) {
            checkout("?productId=" + PRODUCT_ID + "&quantity=2").andExpect(status().isOk());
        }

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(5);
        assertThat(stock.getReserved()).isZero();
    }

    @Test
    void 판매정보가_없는_상품이면_404() throws Exception {
        checkout("?productId=999999&quantity=1")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 수량이_잘못되면_400() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=0").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=-1").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=1.5").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=abc").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID).andExpect(status().isBadRequest());
    }

    /**
     * {@code productId} 를 {@code Long} 으로 받으면 숫자가 아닌 값은 타입 변환 실패가 된다. 공통
     * 예외 핸들러에 맡기면 500 이 되므로 전용 매핑으로 400 을 보장한다.
     */
    @Test
    void productId가_숫자가_아니면_400() throws Exception {
        checkout("?productId=abc&quantity=1").andExpect(status().isBadRequest());
    }

    @Test
    void productId가_없으면_400() throws Exception {
        checkout("?quantity=1").andExpect(status().isBadRequest());
    }

    /**
     * {@code /v1/orders/checkout} 은 {@code /v1/orders/&#123;orderNumber&#125;} 와 경로가 겹칠
     * 수 있다. 리터럴이 먼저 매칭되는 것이 Spring 의 규칙이지만, 주문 조회 컨트롤러로 잘못
     * 흘러가면 비밀번호 헤더가 없다는 이유로 엉뚱한 응답이 나가므로 회귀를 막아둔다.
     */
    @Test
    void checkout_경로가_주문번호_조회로_새지_않는다() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=1")
            .andExpect(status().isOk())
            .andExpect(handler().handlerType(OrderCheckoutController.class));
    }
}
