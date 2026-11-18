package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 생성·결제창의 네이티브 SQL(ON CONFLICT, 조건부 UPDATE)이 실제 PostgreSQL에서 동시 요청에도 맞는지 확인한다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@AutoConfigureMockMvc
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentCreateConcurrencyPostgresTest {
    static final String SCHEMA = "payment_create_" + UUID.randomUUID().toString().replace("-", "");
    static final String TOKEN = "payment-create-postgres-test-credential-32";
    static final int THREADS = 8;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size", () -> THREADS + 2);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> TOKEN);
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("[PAY-CREATE-02] 같은 orderRef 생성 요청 8개가 동시에 와도 결제 1건이고 201은 하나, 나머지는 같은 결제의 200이다")
    void concurrentCreateMakesOnePayment() throws Exception {
        String orderRef = "OD-" + UUID.randomUUID().toString().substring(0, 13);
        String expiresAt =
                Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS).toString();
        String body =
                """
                {"orderRef":"%s","buyerId":"5c2e8f1a-0000-4000-8000-6d4c0b9e7a13","amount":30000,
                 "expiresAt":"%s","sellerAmounts":[
                   {"sellerOrderNumber":"%s-S1","sellerId":"7a1c0000-0000-4000-8000-000000000001",
                    "amount":20000,"commissionRate":0.1000},
                   {"sellerOrderNumber":"%s-S2","sellerId":"7a1c0000-0000-4000-8000-000000000001",
                    "amount":10000,"commissionRate":0.0550}]}
                """
                        .formatted(orderRef, expiresAt, orderRef, orderRef);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post("/v1/internal/payments")
                                                            .header("X-Service-Token", TOKEN)
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(body))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsOnly(201, 200);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment WHERE order_ref = ?",
                                Integer.class,
                                orderRef))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_seller_amount s"
                                        + " JOIN payment p ON p.id = s.payment_id WHERE p.order_ref = ?",
                                Integer.class,
                                orderRef))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("[PAY-CHECKOUT-01] 결제창을 8번 동시에 열면 회차 2–9가 하나씩 나가고 checkout_seq는 9다")
    void concurrentCheckoutsGetDistinctSequences() throws Exception {
        String orderRef = "OD-" + UUID.randomUUID().toString().substring(0, 13);
        assertThat(postCreate(body(orderRef, orderRef))).isEqualTo(201);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    var response =
                                            mvc.perform(
                                                            post(
                                                                            "/v1/internal/payments/{orderRef}/checkouts",
                                                                            orderRef)
                                                                    .header(
                                                                            "X-Service-Token",
                                                                            TOKEN))
                                                    .andReturn()
                                                    .getResponse();
                                    assertThat(response.getStatus()).isEqualTo(201);
                                    return com.jayway.jsonpath.JsonPath.read(
                                            response.getContentAsString(), "$.data.pgOrderId");
                                }));
            }
            start.countDown();
            List<String> pgOrderIds = new ArrayList<>();
            for (Future<String> result : results) {
                pgOrderIds.add(result.get(30, TimeUnit.SECONDS));
            }
            List<String> expected = new ArrayList<>();
            for (int seq = 2; seq <= THREADS + 1; seq++) {
                expected.add(orderRef + "-" + seq);
            }
            assertThat(pgOrderIds).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checkout_seq FROM payment WHERE order_ref = ?",
                                Integer.class,
                                orderRef))
                .isEqualTo(THREADS + 1);
    }

    @Test
    @DisplayName("판매자 주문 번호가 다른 결제에 이미 있으면 PostgreSQL에서도 400이고 새 결제 행이 롤백된다")
    void sellerOrderNumberConflictRollsBack() throws Exception {
        String first = "OD-" + UUID.randomUUID().toString().substring(0, 13);
        assertThat(postCreate(body(first, first))).isEqualTo(201);
        String second = "OD-" + UUID.randomUUID().toString().substring(0, 13);

        assertThat(postCreate(body(second, first))).isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment WHERE order_ref = ?",
                                Integer.class,
                                second))
                .isZero();
    }

    /** 판매자 주문 번호는 sellerPrefix-S1·S2다. */
    static String body(String orderRef, String sellerPrefix) {
        String expiresAt =
                Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS).toString();
        return """
                {"orderRef":"%s","buyerId":"5c2e8f1a-0000-4000-8000-6d4c0b9e7a13","amount":30000,
                 "expiresAt":"%s","sellerAmounts":[
                   {"sellerOrderNumber":"%s-S1","sellerId":"7a1c0000-0000-4000-8000-000000000001",
                    "amount":20000,"commissionRate":0.1000},
                   {"sellerOrderNumber":"%s-S2","sellerId":"7a1c0000-0000-4000-8000-000000000001",
                    "amount":10000,"commissionRate":0.0550}]}
                """
                .formatted(orderRef, expiresAt, sellerPrefix, sellerPrefix);
    }

    int postCreate(String json) throws Exception {
        return mvc.perform(
                        post("/v1/internal/payments")
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
