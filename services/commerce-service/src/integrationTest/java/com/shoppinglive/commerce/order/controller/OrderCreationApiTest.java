package com.shoppinglive.commerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * <p>판정 규칙은 서비스 단위 테스트가 덮으므로, 여기서는 상태코드·응답 필드·헤더 처리처럼 서비스 계층에서 확인할 수 없는 것과 실제 DB 재고 반영을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCreationApiTest extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

    private static final long PRODUCT_ID = 810L;
    private static final long PRICE = 15_000L;
    private static final int INITIAL_STOCK = 5;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private InMemoryShoppingClientStub shoppingClientStub;

    @Autowired private OrderJpaRepository orderRepository;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    @Autowired private TransactionTemplate transactionTemplate;

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
        payload.putAll(overrides);
        return objectMapper.writeValueAsString(payload);
    }

    private ResultActions order(String json, String idempotencyKey) throws Exception {
        var request =
                post("/v1/orders")
                        .header("Authorization", bearer(MEMBER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json);
        if (idempotencyKey != null) {
            request =
                    request.header(OrderCreationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    @DisplayName("주문 생성에 성공하면 201과 주문번호를 반환한다")
    @Test
    void returns201AndOrderNumberForSuccessfulCreation() throws Exception {
        order(body(Map.of()), null)
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.orderNumber")
                                .value(org.hamcrest.Matchers.matchesPattern("OD-\\d{8}-\\d{6}")))
                .andExpect(jsonPath("$.productName").value("테스트 상품"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(15_000))
                .andExpect(jsonPath("$.totalAmount").value(30_000))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    /** 응답에 연락처·비밀번호 해시·멱등키 같은 민감·내부 값이 섞이면 안 된다. */
    @DisplayName("주문 응답에 민감한 정보를 노출하지 않는다")
    @Test
    void omitsSensitiveFieldsFromResponse() throws Exception {
        order(body(Map.of()), "key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyerPhone").doesNotExist())
                .andExpect(jsonPath("$.lookupPasswordHash").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @DisplayName("주문을 생성하면 재고를 예약한다")
    @Test
    void reservesStockWhenCreatingOrder() throws Exception {
        order(body(Map.of("quantity", 2)), null).andExpect(status().isCreated());

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(3);
        assertThat(stock.getReserved()).isEqualTo(2);
    }

    /** 같은 멱등키 재전송은 새 주문을 만들지 않고 기존 주문을 돌려준다. 아무것도 만들지 않았으니 201 이 아니라 200 이다. */
    @DisplayName("같은 멱등 키로 재요청하면 200과 기존 주문번호를 반환한다")
    @Test
    void returns200AndOriginalOrderNumberForIdempotentReplay() throws Exception {
        String first =
                order(body(Map.of()), "key-1")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String firstOrderNumber = objectMapper.readTree(first).get("orderNumber").asText();

        order(body(Map.of()), "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(firstOrderNumber));

        assertThat(orderRepository.findAll()).hasSize(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getReserved()).as("재고도 한 번만 잡힌다").isEqualTo(2);
    }

    static Stream<Map<String, Object>> conflictingRequests() {
        return Stream.of(
                Map.of("productId", 999L),
                Map.of("quantity", 1),
                Map.of("buyerName", "다른 구매자"),
                Map.of("buyerPhone", "010-9999-0000"),
                Map.of("expectedTotalAmount", 999L),
                Map.of("couponId", "different-coupon"));
    }

    @DisplayName("같은 키의 다른 요청은 409로 거절하고 원주문을 노출하지 않는다")
    @ParameterizedTest
    @MethodSource("conflictingRequests")
    void rejectsDifferentRequestForSameKeyWithoutExposingOriginalOrder(Map<String, Object> changes)
            throws Exception {
        order(body(Map.of()), "key-1").andExpect(status().isCreated());
        order(body(changes), "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.orderNumber").doesNotExist())
                .andExpect(jsonPath("$.buyerName").doesNotExist())
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(salesStockRepository.findById(salesId).orElseThrow().getReserved()).isEqualTo(2);
    }

    @DisplayName("활성 주문이 있으면 다른 멱등 키의 새 주문도 거절한다")
    @Test
    void rejectsNewIdempotencyKeyWhileAnOrderIsActive() throws Exception {
        order(body(Map.of("quantity", 1)), "key-1").andExpect(status().isCreated());
        order(body(Map.of("quantity", 1)), "key-2").andExpect(status().isConflict());

        assertThat(orderRepository.findAll()).hasSize(1);
        assertThat(salesStockRepository.findById(salesId).orElseThrow().getReserved()).isEqualTo(1);
    }

    @DisplayName("재고보다 많이 주문하면 409로 응답하고 재고를 유지한다")
    @Test
    void rejectsExcessQuantityWithoutChangingStock() throws Exception {
        order(body(Map.of("quantity", 6)), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(INITIAL_STOCK);
        assertThat(stock.getReserved()).isZero();
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @DisplayName("판매중이 아닌 상품의 주문은 409로 응답한다")
    @Test
    void returns409WhenSaleIsNotActive() throws Exception {
        // @Modifying 쿼리는 트랜잭션 안에서만 실행된다.
        transactionTemplate.executeWithoutResult(
                status -> salesRepository.transitionStatus(salesId, "ON_SALE", "PRIVATE"));

        order(body(Map.of()), null).andExpect(status().isConflict());
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @DisplayName("판매정보가 없는 상품의 주문은 404로 응답한다")
    @Test
    void returns404WhenSalesInformationIsMissing() throws Exception {
        order(body(Map.of("productId", 999_999L)), null).andExpect(status().isNotFound());
    }

    /** 사용자가 본 금액과 현재 금액이 다르면 주문을 만들지 않고 409 로 알린다. 주문서를 열어둔 사이 판매자가 가격을 바꾼 상황이다. */
    @DisplayName("확인 금액이 현재 금액과 다르면 409로 응답하고 주문을 생성하지 않는다")
    @Test
    void rejectsPriceMismatchWithoutCreatingOrder() throws Exception {
        order(body(Map.of("expectedTotalAmount", 25_000L)), null).andExpect(status().isConflict());

        assertThat(orderRepository.findAll()).isEmpty();
        assertThat(salesStockRepository.findById(salesId).orElseThrow().getReserved()).isZero();
    }

    @DisplayName("확인 금액이 현재 금액과 같으면 주문을 생성한다")
    @Test
    void createsOrderWhenExpectedAmountMatchesCurrentPrice() throws Exception {
        order(body(Map.of("expectedTotalAmount", 30_000L)), null).andExpect(status().isCreated());
    }

    @DisplayName("필수값 누락과 잘못된 형식은 400으로 응답한다")
    @Test
    void returns400ForMissingOrMalformedRequiredFields() throws Exception {
        Map<String, Object> noName = new HashMap<>();
        noName.put("buyerName", " ");
        order(body(noName), null).andExpect(status().isBadRequest());

        order(body(Map.of("quantity", 0)), null).andExpect(status().isBadRequest());
        order(body(Map.of("buyerPhone", "전화번호")), null).andExpect(status().isBadRequest());

        assertThat(orderRepository.findAll()).isEmpty();
    }

    /** 재고를 정확히 소진시키면 공개 목록에서 "판매 중" 으로 남지 않도록 품절로 바뀌어야 한다. */
    @DisplayName("주문으로 재고를 모두 예약하면 품절로 변경한다")
    @Test
    void marksSaleSoldOutWhenStockIsExhausted() throws Exception {
        order(body(Map.of("quantity", 5)), null).andExpect(status().isCreated());

        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.SOLD_OUT);
    }
}
