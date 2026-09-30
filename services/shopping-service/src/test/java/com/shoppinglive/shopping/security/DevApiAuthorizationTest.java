package com.shoppinglive.shopping.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.shoppinglive.shopping.image.domain.ImageFormat;
import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"shopping.dev-api.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:shopping_dev_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DevApiAuthorizationTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ProductImageRepository images;

    @Test
    void enabledLocalTestResetStillRequiresAdministratorBeforeDeleting() throws Exception {
        images.saveAndFlush(new ProductImage("dev-auth.png", ImageFormat.PNG, 1, 1, 1, "dev-auth.png"));
        mvc.perform(delete("/v1/dev/product-images")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", userBearer())).andExpect(status().isForbidden());
        assertThat(images.count()).isEqualTo(1);
        mvc.perform(delete("/v1/dev/product-images").header("Authorization", adminBearer())).andExpect(status().isOk());
        assertThat(images.count()).isZero();
    }
}
