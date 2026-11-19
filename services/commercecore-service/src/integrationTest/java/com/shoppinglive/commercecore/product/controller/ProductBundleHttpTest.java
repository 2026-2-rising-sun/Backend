package com.shoppinglive.commercecore.product.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.product.service.ProductBundleRegistrationService;
import com.shoppinglive.commercecore.product.support.*;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = ProductHttpTestApplication.class,
        properties =
                "spring.datasource.url=jdbc:h2:mem:bundle_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@DisplayName("통합 등록 multipart와 실제 판매자 인증 계약")
class ProductBundleHttpTest extends ProductSecuritySupport {
    @Autowired MockMvc mvc;
    @MockitoBean ProductBundleRegistrationService registration;

    MockMultipartFile json(String skus) {
        return new MockMultipartFile(
                "request",
                "",
                "application/json",
                ("{\"name\":\"상품\",\"description\":\"설명\",\"skus\":" + skus + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    MockMultipartFile file() {
        return new MockMultipartFile("file", "test.png", "image/png", new byte[] {1, 2});
    }

    @Test
    @DisplayName("인증된 판매자의 파일과 JSON을 함께 받아 최초 SKU 결과를 201로 반환한다")
    void acceptsMultipart() throws Exception {
        when(registration.register(eq(JwtTestTokens.SELLER), eq("key"), any(), any(), any(), any()))
                .thenReturn(
                        new ProductBundleResponse(
                                7L,
                                9L,
                                "/image",
                                true,
                                List.of(
                                        new ProductBundleResponse.RegisteredSku(
                                                11L, null, 100, 0, 0, SalesStatus.SOLD_OUT))));
        mvc.perform(
                        multipart("/v1/seller/products/registrations")
                                .file(json("[{\"price\":100,\"initialStock\":0}]"))
                                .file(file())
                                .header("Authorization", sellerBearer())
                                .header("X-Idempotency-Key", "key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productId").value(7))
                .andExpect(jsonPath("$.data.saleEnabled").value(true))
                .andExpect(jsonPath("$.data.skus[0].status").value("SOLD_OUT"));
    }

    @Test
    @DisplayName("비인증과 구매자 요청은 등록 서비스 호출 전에 차단한다")
    void requiresSeller() throws Exception {
        var request = multipart("/v1/seller/products/registrations").file(json("[]")).file(file());
        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart("/v1/seller/products/registrations")
                                .file(json("[]"))
                                .file(file())
                                .header("Authorization", userBearer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registration);
    }

    @Test
    @DisplayName("파일·JSON·멱등키 누락과 잘못된 SKU 입력은 400이며 등록하지 않는다")
    void rejectsInvalidInput() throws Exception {
        String token = sellerBearer();
        mvc.perform(
                        multipart("/v1/seller/products/registrations")
                                .file(file())
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/v1/seller/products/registrations")
                                .file(json("[]"))
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        for (String skus :
                List.of(
                        "[]",
                        "[null]",
                        "[{\"price\":0,\"initialStock\":-1}]",
                        "[{\"price\":100.5,\"initialStock\":0}]",
                        "[{\"price\":100,\"initialStock\":1.5}]",
                        "[{\"price\":\"100\",\"initialStock\":0}]",
                        "[{\"price\":100,\"initialStock\":\"1\"}]"))
            mvc.perform(
                            multipart("/v1/seller/products/registrations")
                                    .file(json(skus))
                                    .file(file())
                                    .header("Authorization", token)
                                    .header("X-Idempotency-Key", "key"))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/v1/seller/products/registrations")
                                .file(json("[{\"price\":100,\"initialStock\":0}]"))
                                .file(file())
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
    }
}
