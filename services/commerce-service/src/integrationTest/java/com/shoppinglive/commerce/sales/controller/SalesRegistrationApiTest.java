package com.shoppinglive.commerce.sales.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매 1 등록 API 의 HTTP 계약 검증.
 *
 * <p><b>왜 서비스 단위 테스트로 충분하지 않나:</b> 서비스가 올바른 예외를 던져도, 그 예외가 실제 응답에서 어떤 상태코드가 되는지는 별개 문제다. {@code
 * common-module} 의 {@code GlobalExceptionHandler} 에 {@code @ExceptionHandler(Exception.class)} 가 있어
 * {@code @ResponseStatus} 로만 상태를 지정한 예외는 500 으로 덮여버린다 (Spring 이 {@code
 * ExceptionHandlerExceptionResolver} 를 {@code ResponseStatusExceptionResolver} 보다 먼저 적용하기 때문). 이
 * 테스트가 그 함정을 막는 회귀 방지선이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesRegistrationApiTest
        extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

    private static final long REGISTERED_PRODUCT_ID = 900L;
    private static final long UNKNOWN_PRODUCT_ID = 999L;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private InMemoryShoppingClientStub shoppingClientStub;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    @BeforeEach
    void setUp() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
        shoppingClientStub.register(new ProductSnapshot(REGISTERED_PRODUCT_ID, "테스트 상품", null));
    }

    @AfterEach
    void tearDown() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
    }

    /**
     * null 을 그대로 담아야 "필수값 누락" 을 재현할 수 있어서 {@link HashMap} 을 쓴다 ({@code Map.of} 는 null 값을 허용하지 않는다).
     */
    private String body(Object productId, Object price, Object initialStock) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", productId);
        payload.put("price", price);
        payload.put("initialStock", initialStock);
        return objectMapper.writeValueAsString(payload);
    }

    private ResultActions register(String json) throws Exception {
        return mockMvc.perform(
                post("/v1/sales")
                        .header("Authorization", sellerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    @DisplayName("판매 등록에 성공하면 201과 READY 상태를 반환한다")
    @Test
    void returns201AndReadyStatusAfterRegistration() throws Exception {
        register(body(REGISTERED_PRODUCT_ID, 15_000, 10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(REGISTERED_PRODUCT_ID))
                .andExpect(jsonPath("$.price").value(15_000))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @DisplayName("없는 상품을 판매 등록하면 404로 응답한다")
    @Test
    void returns404ForMissingProduct() throws Exception {
        register(body(UNKNOWN_PRODUCT_ID, 15_000, 10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @DisplayName("이미 판매정보가 있는 상품을 등록하면 409로 응답한다")
    @Test
    void returns409ForAlreadyRegisteredProduct() throws Exception {
        register(body(REGISTERED_PRODUCT_ID, 15_000, 10)).andExpect(status().isCreated());

        register(body(REGISTERED_PRODUCT_ID, 20_000, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @DisplayName("판매 등록 가격이 0 이하면 400으로 응답한다")
    @Test
    void returns400ForNonPositivePrice() throws Exception {
        register(body(REGISTERED_PRODUCT_ID, 0, 10)).andExpect(status().isBadRequest());
        register(body(REGISTERED_PRODUCT_ID, -1, 10)).andExpect(status().isBadRequest());
    }

    @DisplayName("판매 등록 재고가 음수면 400으로 응답한다")
    @Test
    void returns400ForNegativeInitialStock() throws Exception {
        register(body(REGISTERED_PRODUCT_ID, 15_000, -1)).andExpect(status().isBadRequest());
    }

    @DisplayName("판매 등록 필수값이 없으면 400으로 응답한다")
    @Test
    void returns400ForMissingRequiredFields() throws Exception {
        register(body(null, 15_000, 10)).andExpect(status().isBadRequest());
        register(body(REGISTERED_PRODUCT_ID, null, 10)).andExpect(status().isBadRequest());
        register(body(REGISTERED_PRODUCT_ID, 15_000, null)).andExpect(status().isBadRequest());
    }

    /** 등록 실패가 판매정보를 남기면 같은 상품을 다시 등록할 때 409 가 나서 영영 막힌다. */
    @DisplayName("없는 상품의 등록이 실패해도 같은 상품의 다음 등록을 막지 않는다")
    @Test
    void allowsRetryAfterMissingProductRegistrationFails() throws Exception {
        register(body(UNKNOWN_PRODUCT_ID, 15_000, 10)).andExpect(status().isNotFound());

        shoppingClientStub.register(new ProductSnapshot(UNKNOWN_PRODUCT_ID, "뒤늦게 생긴 상품", null));

        register(body(UNKNOWN_PRODUCT_ID, 15_000, 10)).andExpect(status().isCreated());
    }
}
