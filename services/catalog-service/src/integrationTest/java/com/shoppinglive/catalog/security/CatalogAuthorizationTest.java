package com.shoppinglive.catalog.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.catalog.client.commercecore.SalesInfo;
import com.shoppinglive.catalog.client.commercecore.SalesInfoClient;
import com.shoppinglive.catalog.client.commercecore.SalesStatus;
import com.shoppinglive.catalog.product.repository.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:shopping_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class CatalogAuthorizationTest extends CatalogSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ApplicationContext context;
    @MockitoBean SalesInfoClient sales;
    private long productId;
    private long imageId;

    @BeforeEach
    void fixture() {
        productId =
                products.saveAndFlush(
                                com.shoppinglive.catalog.product.support.ProductReplicaFixtures
                                        .product("Coffee", "Coffee", 100L, null))
                        .getId();
        when(sales.findByProductId(productId))
                .thenReturn(
                        java.util.Optional.of(
                                new SalesInfo(productId, 101L, 1000L, SalesStatus.ON_SALE, 5)));
    }

    @AfterEach
    void clean() {
        products.deleteAll();
    }

    @DisplayName("공개 상품 조회와 최소 probe는 익명 접근을 허용한다")
    @Test
    void publicReadsAndMinimalProbesRemainAnonymous() throws Exception {
        for (String path :
                List.of(
                        "/v1/products",
                        "/v1/products/" + productId,
                        "/v1/products/" + productId + "/purchase-check?quantity=1",
                        "/actuator/health/readiness",
                        "/actuator/health/liveness")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }

        for (String path :
                List.of("/actuator/health", "/actuator/info", "/actuator/env", "/unclassified")) {
            mvc.perform(get(path).header("Authorization", sellerBearer()))
                    .andExpect(status().isForbidden());
        }
    }

    @DisplayName("익명 probe는 장애를 보고하되 내부 세부 정보를 노출하지 않는다")
    @Test
    void anonymousProbesReportFailuresWithoutLeakingHealthDetails() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            mvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        }
    }
}
