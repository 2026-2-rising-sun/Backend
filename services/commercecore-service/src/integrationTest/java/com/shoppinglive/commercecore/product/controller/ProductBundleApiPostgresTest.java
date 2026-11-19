package com.shoppinglive.commercecore.product.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(
        classes = ProductHttpTestApplication.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "shoppinglive.outbox.relay.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("실제 판매자 인증·multipart·PostgreSQL·로컬 이미지 통합 등록")
class ProductBundleApiPostgresTest extends ProductSecuritySupport {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) throws Exception {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        String schema = "bundle_api_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var c =
                        java.sql.DriverManager.getConnection(
                                url,
                                System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                                System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
                var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        p.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
        p.add("spring.datasource.username", () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        p.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.schemas", () -> schema);
    }

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("파일과 두 SKU를 한 번에 등록하고 응답 유실 재시도는 같은 식별자와 최초 결과를 반환한다")
    void registersAndReplaysThroughHttp() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, 1), "png", bytes);
        String payload =
                """
                {"name":"통합 상품","description":"설명","skus":[
                  {"optionLabel":"빨강","price":100,"initialStock":2},
                  {"optionLabel":"파랑","price":200,"initialStock":0}]}
                """;
        var file = new MockMultipartFile("file", "test.png", "image/png", bytes.toByteArray());
        var json =
                new MockMultipartFile(
                        "request",
                        "",
                        "application/json",
                        payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String first = null;
        for (int i = 0; i < 2; i++) {
            var response =
                    mvc.perform(
                                    multipart("/v1/seller/products/registrations")
                                            .file(file)
                                            .file(json)
                                            .header("Authorization", sellerBearer())
                                            .header("X-Idempotency-Key", "http-retry"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.data.saleEnabled").value(true))
                            .andExpect(jsonPath("$.data.skus[1].status").value("SOLD_OUT"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (first == null) first = response;
            else
                assertThat(mapper.readTree(response).path("data"))
                        .isEqualTo(mapper.readTree(first).path("data"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class))
                .isEqualTo(3);
    }
}
