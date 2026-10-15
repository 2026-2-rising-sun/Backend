package com.shoppinglive.shopping.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.shopping.product.entity.ProductImage;
import com.shoppinglive.shopping.product.repository.ProductImageRepository;
import com.shoppinglive.shopping.storage.ImageFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "shopping.dev-api.enabled=true",
            "spring.datasource.url=jdbc:h2:mem:shopping_dev_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DevApiAuthorizationTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ProductImageRepository images;

    @DisplayName("개발 이미지 초기화는 local·test에서 활성화해도 삭제 전 관리자 인증을 요구한다")
    @Test
    void enabledLocalTestResetStillRequiresSellerBeforeDeleting() throws Exception {
        images.saveAndFlush(
                new ProductImage("dev-auth.png", ImageFormat.PNG, 1, 1, 1, "dev-auth.png"));
        mvc.perform(delete("/v1/dev/product-images")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", userBearer()))
                .andExpect(status().isForbidden());
        assertThat(images.count()).isEqualTo(1);
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", sellerBearer()))
                .andExpect(status().isOk());
        assertThat(images.count()).isZero();
    }
}
