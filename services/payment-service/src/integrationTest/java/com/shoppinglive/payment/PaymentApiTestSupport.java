package com.shoppinglive.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** H2(PostgreSQL 모드)에서 결제 내부 API를 MockMvc로 부르는 통합 테스트의 공통 설정. 테스트마다 다른 orderRef를 쓴다. */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "spring.datasource.url=jdbc:h2:mem:payment_api;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.kafka.bootstrap-servers=localhost:1",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.core-service-token=" + PaymentApiTestSupport.TOKEN
        })
@AutoConfigureMockMvc
abstract class PaymentApiTestSupport {
    static final String TOKEN = "payment-api-test-service-credential-32";
    static final String BUYER = "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13";
    static final String OTHER_BUYER = "5c2e8f1a-0000-4000-8000-000000000002";
    static final String SELLER = "7a1c0000-0000-4000-8000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static String newOrderRef() {
        return "OD-" + UUID.randomUUID().toString().substring(0, 13);
    }

    static String future() {
        return Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** 판매자 주문 2개(20000 + 10000)로 30000원 결제 생성 본문. */
    static String body(String orderRef, String buyerId, long amount, String expiresAt) {
        return """
                {"orderRef":"%s","buyerId":"%s","amount":%d,"expiresAt":"%s",
                 "sellerAmounts":[
                   {"sellerOrderNumber":"%s-S1","sellerId":"%s","amount":20000,"commissionRate":0.1000},
                   {"sellerOrderNumber":"%s-S2","sellerId":"%s","amount":%d,"commissionRate":0.0550}]}
                """
                .formatted(
                        orderRef,
                        buyerId,
                        amount,
                        expiresAt,
                        orderRef,
                        SELLER,
                        orderRef,
                        SELLER,
                        amount - 20000);
    }

    ResultActions createJson(String json) throws Exception {
        return mvc.perform(
                post("/v1/internal/payments")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    ResultActions create(String orderRef) throws Exception {
        return createJson(body(orderRef, BUYER, 30000, future()));
    }

    ResultActions checkout(String orderRef) throws Exception {
        return mvc.perform(
                post("/v1/internal/payments/{orderRef}/checkouts", orderRef)
                        .header("X-Service-Token", TOKEN));
    }

    ResultActions query(String orderRef) throws Exception {
        return mvc.perform(
                get("/v1/internal/payments/{orderRef}", orderRef).header("X-Service-Token", TOKEN));
    }

    /** 다른 PR(승인·만료)이 만들 상태를 테스트에서 직접 만든다. */
    void setStatus(String orderRef, String status) {
        jdbc.update("UPDATE payment SET status = ? WHERE order_ref = ?", status, orderRef);
    }

    void expire(String orderRef) {
        jdbc.update(
                "UPDATE payment SET expires_at = ? WHERE order_ref = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                orderRef);
    }
}
