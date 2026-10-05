package com.shoppinglive.shopping.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.shopping.image.domain.ImageFormat;
import com.shoppinglive.shopping.image.application.ImageStorage;
import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:shopping_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ShoppingAuthorizationTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired ImageStorage storage;
    @Autowired ApplicationContext context;
    @MockitoBean SalesInfoClient sales;
    private long productId;
    private long imageId;

    @BeforeEach
    void fixture() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = images.saveAndFlush(new ProductImage("auth.png", ImageFormat.PNG, png.size(), 1, 1, "auth.png"));
        storage.store("auth.png", new ByteArrayInputStream(png.toByteArray()), png.size(), "image/png");
        imageId = image.getId();
        productId = products.saveAndFlush(new Product("Coffee", "Coffee", image.getId(), "auth-product")).getId();
        when(sales.findByProductIds(anyCollection())).thenReturn(Map.of(productId,
            new SalesInfo(productId, 101L, 1000L, SalesStatus.ON_SALE, 5)));
        when(sales.findByProductId(productId)).thenReturn(java.util.Optional.of(
            new SalesInfo(productId, 101L, 1000L, SalesStatus.ON_SALE, 5)));
    }

    @AfterEach
    void clean() { products.deleteAll(); images.deleteAll(); storage.delete("auth.png"); }

    @Test
    void publicReadsAndMinimalProbesRemainAnonymous() throws Exception {
        for (String path : List.of("/v1/products", "/v1/products/" + productId,
            "/v1/products/" + productId + "/purchase-check?quantity=1",
            "/v1/product-images/" + imageId, "/actuator/health/readiness", "/actuator/health/liveness")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(get("/v1/product-images/999999")).andExpect(status().isNotFound());
        for (String path : List.of("/actuator/health", "/actuator/info", "/actuator/env", "/unclassified")) {
            mvc.perform(get(path).header("Authorization", adminBearer())).andExpect(status().isForbidden());
        }
    }

    @Test
    void sessionRevocationAndAuthorityOutageFailClosedWithoutBlockingPublicOrInternalReads() throws Exception {
        String token = adminBearer();
        mvc.perform(get("/v1/admin/products").header("Authorization", token)).andExpect(status().isOk());
        accessSessions.revoke();
        mvc.perform(get("/v1/admin/products").header("Authorization", token)).andExpect(status().isUnauthorized());
        accessSessions.fail();
        mvc.perform(get("/v1/admin/products").header("Authorization", token)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/v1/products")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/v1/internal/products/" + productId).header("X-Service-Token", COMMERCE_KEY))
            .andExpect(status().isOk());
    }

    @Test
    void allAdminMethodsDenyAnonymousAndUserBeforeSideEffects() throws Exception {
        for (String route : List.of("GET /v1/admin/products", "GET /v1/admin/products/1",
            "POST /v1/admin/products", "PATCH /v1/admin/products/1", "POST /v1/admin/product-images")) {
            String[] parts = route.split(" ");
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", userBearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        assertThat(products.count()).isEqualTo(1);
        assertThat(images.count()).isEqualTo(1);
        mvc.perform(get("/v1/admin/products/" + productId).header("Authorization", adminBearer()))
            .andExpect(status().isOk());
    }

    @Test
    void invalidTokensAndClaimHeadersCannotCreateAdministrator() throws Exception {
        var claims = TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER"));
        for (String token : List.of("invalid", new JwtTestTokens().token(JwtTestTokens.SELLER, Set.of("SELLER")),
            TOKENS.sign(claims.expirationTime(Date.from(Instant.now().minusSeconds(120))).build()),
            TOKENS.sign(TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER")).issuer("other").build()),
            TOKENS.sign(TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER")).audience("other").build()))) {
            mvc.perform(get("/v1/admin/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/v1/admin/products").header("X-Member-Id", JwtTestTokens.SELLER).header("X-Roles", "SELLER"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceIdentityAndUserIdentityCannotSubstituteForOneAnother() throws Exception {
        for (String key : List.of(COMMERCE_KEY, LIVE_KEY)) {
            mvc.perform(get("/v1/internal/products").param("ids", "" + productId).header("X-Service-Token", key))
                .andExpect(status().isOk());
            mvc.perform(get("/v1/internal/products/" + productId).header("X-Service-Token", key))
                .andExpect(status().isOk());
            mvc.perform(get("/v1/admin/products").header("X-Service-Token", key)).andExpect(status().isUnauthorized());
            mvc.perform(post("/v1/internal/products").header("X-Service-Token", key)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/v1/internal/products").header("Authorization", adminBearer())).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/internal/products").header("X-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/internal/products").header("X-Service-Token", OTHER_KEY).header("X-Service-Caller", "commerce"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", adminBearer())).andExpect(status().isForbidden());
        mvc.perform(post("/v1/products").header("Authorization", adminBearer())).andExpect(status().isForbidden());
    }

    @Test
    void anonymousProbesReportFailuresWithoutLeakingHealthDetails() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isServiceUnavailable());
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        }
    }
}
