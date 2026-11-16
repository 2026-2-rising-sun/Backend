package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

class HttpPaymentRelayClientTest {
    MockRestServiceServer server;
    HttpPaymentRelayClient client;

    @BeforeEach
    void setUp() {
        var builder =
                RestClient.builder()
                        .baseUrl("http://payment-contract")
                        .defaultHeader("X-Service-Token", "relay-test");
        server = MockRestServiceServer.bindTo(builder).build();
        var http = builder.build();
        client =
                new HttpPaymentRelayClient(
                        http, http, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("생성은 팀원 요청 필드·서비스 토큰을 보내고 ApiResponse data와 오프셋 시각을 읽는다")
    void serializesCreationAndReadsWrappedData() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", "relay-test"))
                .andExpect(jsonPath("$.orderRef").value("ORDER-123"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-16T01:10:00Z"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.sellerAmounts[0].commissionRate").value(0.125))
                .andRespond(
                        withStatus(HttpStatus.CREATED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"success\":true,\"data\":{\"paymentId\":5,\"status\":\"CREATED\",\"pgOrderId\":\"ORDER-123-1\",\"amount\":5000,\"expiresAt\":\"2026-11-16T10:10:00+09:00\"},\"error\":null}"));
        var result =
                client.create(
                        new Create(
                                "ORDER-123",
                                "11111111-1111-4111-8111-111111111111",
                                5000,
                                Instant.parse("2026-11-16T01:10:00Z"),
                                List.of(
                                        new SellerAmount(
                                                "ORDER-123-S1",
                                                "33333333-3333-4333-8333-333333333333",
                                                5000,
                                                new BigDecimal("0.1250")))));
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().paymentId()).isEqualTo(5);
        assertThat(result.data().expiresAt()).isEqualTo(Instant.parse("2026-11-16T01:10:00Z"));
        server.verify();
    }

    @Test
    @DisplayName("승인202는 PENDING과 Location을 보존하고 확정 실패로 바꾸지 않는다")
    void pendingApprovalRetainsLocationAndState() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123/approval"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.buyerId").value("11111111-1111-4111-8111-111111111111"))
                .andRespond(
                        withStatus(HttpStatus.ACCEPTED)
                                .header("Location", "/v1/internal/payments/ORDER-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"success\":true,\"data\":{\"paymentId\":5,\"status\":\"PENDING\"},\"error\":null}"));
        var result =
                client.approve(
                        "ORDER-123",
                        new Approval(
                                "11111111-1111-4111-8111-111111111111",
                                "key",
                                "ORDER-123-1",
                                5000));
        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.data().status()).isEqualTo("PENDING");
        assertThat(result.location()).isEqualTo("/v1/internal/payments/ORDER-123");
        server.verify();
    }

    @Test
    @DisplayName("승인422는 예외로 잃지 않고 DECLINED 업무 응답과 PG 사유를 읽는다")
    void declinedApprovalReadsErrorAndProviderCode() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123/approval"))
                .andRespond(
                        withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"success\":false,\"data\":{\"status\":\"DECLINED\",\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"거절\"},\"error\":{\"code\":\"DECLINED\",\"message\":\"카드 거절\"}}"));
        var result =
                client.approve(
                        "ORDER-123",
                        new Approval(
                                "11111111-1111-4111-8111-111111111111",
                                "key",
                                "ORDER-123-1",
                                5000));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.errorCode()).isEqualTo("DECLINED");
        assertThat(result.data().code()).isEqualTo("REJECT_CARD_PAYMENT");
        server.verify();
    }

    @Test
    @DisplayName("Core 트랜잭션 중에는 생성·승인·조회·결제창 HTTP를 실행하지 않는다")
    void refusesHttpInsideCoreTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.get("ORDER-123"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.checkout("ORDER-123"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.create(null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.approve("ORDER-123", null))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
