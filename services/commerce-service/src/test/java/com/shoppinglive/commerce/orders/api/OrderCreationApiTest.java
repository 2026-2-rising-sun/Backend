package com.shoppinglive.commerce.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 2 생성 API 의 HTTP 계약 검증.
 *
 * <p>판정 규칙은 서비스 단위 테스트가 덮으므로, 여기서는 상태코드·응답 필드·헤더 처리처럼
 * 서비스 계층에서 확인할 수 없는 것과 실제 DB 재고 반영을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCreationApiTest {

    private static final long PRODUCT_ID = 810L;
    private static final long PRICE = 15_000L;
    private static final int INITIAL_STOCK = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryShoppingClientStub shoppingClientStub;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long salesId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
        shoppingClientStub.register(new ProductSnapshot(PRODUCT_ID, "테스트 상품", null));

        Sales sales = salesRepository.save(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE));
        salesId = sales.getId();
        salesStockRepository.save(new SalesStock(salesId, INITIAL_STOCK, 0));
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
    }

    private String body(Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", PRODUCT_ID);
        payload.put("quantity", 2);
        payload.put("buyerName", "홍길동");
        payload.put("buyerPhone", "010-1234-5678");
        payload.put("lookupPassword", "secret");
        payload.putAll(overrides);
        return objectMapper.writeValueAsString(payload);
    }

    private ResultActions order(String json, String idempotencyKey) throws Exception {
        var request = post("/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
        if (idempotencyKey != null) {
            request = request.header(
                OrderCreationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    @Test
    void 주문_성공하면_201과_주문번호를_돌려준다() throws Exception {
        order(body(Map.of()), null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers
                .matchesPattern("OD-\\d{8}-\\d{6}")))
            .andExpect(jsonPath("$.productName").value("테스트 상품"))
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.unitPrice").value(15_000))
            .andExpect(jsonPath("$.totalAmount").value(30_000))
            .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.expiresAt").exists());
    }

    /**
     * 응답에 연락처·비밀번호 해시·멱등키 같은 민감·내부 값이 섞이면 안 된다.
     */
    @Test
    void 응답에_민감정보가_없다() throws Exception {
        order(body(Map.of()), "key-1")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.buyerPhone").doesNotExist())
            .andExpect(jsonPath("$.lookupPasswordHash").doesNotExist())
            .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void 주문하면_재고가_배정된다() throws Exception {
        order(body(Map.of("quantity", 2)), null).andExpect(status().isCreated());

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(3);
        assertThat(stock.getReserved()).isEqualTo(2);
    }

    /**
     * 같은 멱등키 재전송은 새 주문을 만들지 않고 기존 주문을 돌려준다. 아무것도 만들지 않았으니
     * 201 이 아니라 200 이다.
     */
    @Test
    void 같은_멱등키로_다시_보내면_200과_같은_주문번호() throws Exception {
        String first = order(body(Map.of()), "key-1")
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String firstOrderNumber = objectMapper.readTree(first).get("orderNumber").asText();

        order(body(Map.of()), "key-1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderNumber").value(firstOrderNumber));

        assertThat(orderRepository.findAll()).hasSize(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getReserved()).as("재고도 한 번만 잡힌다").isEqualTo(2);
    }

    @Test
    void 멱등키가_다르면_별개_주문이_만들어진다() throws Exception {
        order(body(Map.of("quantity", 1)), "key-1").andExpect(status().isCreated());
        order(body(Map.of("quantity", 1)), "key-2").andExpect(status().isCreated());

        assertThat(orderRepository.findAll()).hasSize(2);
    }

    @Test
    void 재고보다_많이_주문하면_409이고_재고는_그대로() throws Exception {
        order(body(Map.of("quantity", 6)), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(INITIAL_STOCK);
        assertThat(stock.getReserved()).isZero();
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    void 판매중이_아니면_409() throws Exception {
        // @Modifying 쿼리는 트랜잭션 안에서만 실행된다.
        transactionTemplate.executeWithoutResult(status ->
            salesRepository.transitionStatus(salesId, "ON_SALE", "PRIVATE"));

        order(body(Map.of()), null).andExpect(status().isConflict());
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    void 판매정보가_없는_상품이면_404() throws Exception {
        order(body(Map.of("productId", 999_999L)), null).andExpect(status().isNotFound());
    }

    /**
     * 사용자가 본 금액과 현재 금액이 다르면 주문을 만들지 않고 409 로 알린다. 주문서를 열어둔
     * 사이 관리자가 가격을 바꾼 상황이다.
     */
    @Test
    void 확인_금액이_현재_금액과_다르면_409이고_주문을_만들지_않는다() throws Exception {
        order(body(Map.of("expectedTotalAmount", 25_000L)), null)
            .andExpect(status().isConflict());

        assertThat(orderRepository.findAll()).isEmpty();
        assertThat(salesStockRepository.findById(salesId).orElseThrow().getReserved()).isZero();
    }

    @Test
    void 확인_금액이_맞으면_주문된다() throws Exception {
        order(body(Map.of("expectedTotalAmount", 30_000L)), null)
            .andExpect(status().isCreated());
    }

    @Test
    void 필수값이_빠지거나_형식이_틀리면_400() throws Exception {
        Map<String, Object> noName = new HashMap<>();
        noName.put("buyerName", " ");
        order(body(noName), null).andExpect(status().isBadRequest());

        order(body(Map.of("quantity", 0)), null).andExpect(status().isBadRequest());
        order(body(Map.of("buyerPhone", "전화번호")), null).andExpect(status().isBadRequest());
        order(body(Map.of("lookupPassword", "1")), null).andExpect(status().isBadRequest());

        assertThat(orderRepository.findAll()).isEmpty();
    }

    /**
     * 재고를 정확히 소진시키면 공개 목록에서 "판매 중" 으로 남지 않도록 품절로 바뀌어야 한다.
     */
    @Test
    void 재고를_다_쓰면_품절로_바뀐다() throws Exception {
        order(body(Map.of("quantity", 5)), null).andExpect(status().isCreated());

        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
            .isEqualTo(SalesStatus.SOLD_OUT);
    }
}
