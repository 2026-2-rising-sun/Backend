package com.shoppinglive.commercecore.product.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.entity.ProductImage;
import com.shoppinglive.commercecore.product.repository.ProductImageRepository;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.product.support.ProductSecuritySupport;
import com.shoppinglive.commercecore.storage.ImageFormat;
import com.shoppinglive.commercecore.storage.ImageStorage;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = com.shoppinglive.commercecore.product.support.ProductHttpTestApplication.class,
        properties =
                "spring.datasource.url=jdbc:h2:mem:shopping_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ProductAuthorizationTest extends ProductSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired ImageStorage storage;
    @Autowired ApplicationContext context;
    private long productId;
    private long imageId;

    @BeforeEach
    void fixture() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        var image =
                images.saveAndFlush(
                        new ProductImage(
                                "auth.png", ImageFormat.PNG, png.size(), 1, 1, "auth.png"));
        storage.store(
                "auth.png", new ByteArrayInputStream(png.toByteArray()), png.size(), "image/png");
        imageId = image.getId();
        productId =
                products.saveAndFlush(
                                new Product(
                                        "Coffee",
                                        "Coffee",
                                        image.getId(),
                                        "auth-product",
                                        JwtTestTokens.SELLER))
                        .getId();
    }

    @AfterEach
    void clean() {
        products.deleteAll();
        images.deleteAll();
        storage.delete("auth.png");
    }

    @DisplayName("세션 폐기·검증 장애는 보호 경로만 차단하고 공개·내부 조회를 유지한다")
    @Test
    void sessionRevocationAndAuthorityOutageFailClosedWithoutBlockingPublicOrInternalReads()
            throws Exception {
        String token = sellerBearer();
        mvc.perform(get("/v1/seller/products").header("Authorization", token))
                .andExpect(status().isOk());
        accessSessions.revoke();
        mvc.perform(get("/v1/seller/products").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        accessSessions.fail();
        mvc.perform(get("/v1/seller/products").header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));

        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/v1/internal/products/" + productId).header("X-Service-Token", LIVE_KEY))
                .andExpect(status().isOk());
    }

    @DisplayName("모든 판매자 메서드는 부수 효과 전에 익명·일반 사용자를 거절한다")
    @Test
    void allSellerMethodsDenyAnonymousAndUserBeforeSideEffects() throws Exception {
        for (String route :
                List.of(
                        "GET /v1/seller/products",
                        "GET /v1/seller/products/1",
                        "POST /v1/seller/products",
                        "PATCH /v1/seller/products/1",
                        "POST /v1/seller/product-images")) {
            String[] parts = route.split(" ");
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mvc.perform(
                            request(HttpMethod.valueOf(parts[0]), parts[1])
                                    .header("Authorization", userBearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        assertThat(products.count()).isEqualTo(1);
        assertThat(images.count()).isEqualTo(1);
        mvc.perform(get("/v1/seller/products/" + productId).header("Authorization", sellerBearer()))
                .andExpect(status().isOk());
    }

    @DisplayName("잘못된 토큰이나 클레임 헤더로 판매자 권한을 얻을 수 없다")
    @Test
    void invalidTokensAndClaimHeadersCannotCreateSeller() throws Exception {
        var claims = TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER"));
        for (String token :
                List.of(
                        "invalid",
                        new JwtTestTokens().token(JwtTestTokens.SELLER, Set.of("SELLER")),
                        TOKENS.sign(
                                claims.expirationTime(Date.from(Instant.now().minusSeconds(120)))
                                        .build()),
                        TOKENS.sign(
                                TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER"))
                                        .issuer("other")
                                        .build()),
                        TOKENS.sign(
                                TOKENS.claims(JwtTestTokens.SELLER, Set.of("SELLER"))
                                        .audience("other")
                                        .build()))) {
            mvc.perform(get("/v1/seller/products").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        post("/v1/seller/products")
                                .header("X-Member-Id", JwtTestTokens.SELLER)
                                .header("X-Roles", "SELLER"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("서비스와 사용자 인증 정보는 서로 대신 사용할 수 없다")
    @Test
    void serviceIdentityAndUserIdentityCannotSubstituteForOneAnother() throws Exception {
        for (String key : List.of(LIVE_KEY)) {
            mvc.perform(
                            get("/v1/internal/products")
                                    .param("ids", "" + productId)
                                    .header("X-Service-Token", key))
                    .andExpect(status().isOk());
            mvc.perform(get("/v1/internal/products/" + productId).header("X-Service-Token", key))
                    .andExpect(status().isOk());
            mvc.perform(get("/v1/seller/products").header("X-Service-Token", key))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/v1/internal/products").header("X-Service-Token", key))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/v1/internal/products").header("Authorization", sellerBearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/internal/products").header("X-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/v1/internal/products")
                                .header("X-Service-Token", OTHER_KEY)
                                .header("X-Service-Caller", "live"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", sellerBearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/products").header("Authorization", sellerBearer()))
                .andExpect(status().isForbidden());
    }
}
