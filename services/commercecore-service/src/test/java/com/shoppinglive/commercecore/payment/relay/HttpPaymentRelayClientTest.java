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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.json.JsonCompareMode;
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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {
                                          "orderRef":"ORDER-123",
                                          "buyerId":"11111111-1111-4111-8111-111111111111",
                                          "amount":5000,
                                          "expiresAt":"2026-11-16T01:10:00Z",
                                          "sellerAmounts":[
                                            {"sellerOrderNumber":"ORDER-123-S1","sellerId":"33333333-3333-4333-8333-333333333333","amount":3000,"commissionRate":0.1250},
                                            {"sellerOrderNumber":"ORDER-123-S2","sellerId":"44444444-4444-4444-8444-444444444444","amount":2000,"commissionRate":0.0550}
                                          ]
                                        }
                                        """,
                                        JsonCompareMode.STRICT))
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
                                                3000,
                                                new BigDecimal("0.1250")),
                                        new SellerAmount(
                                                "ORDER-123-S2",
                                                "44444444-4444-4444-8444-444444444444",
                                                2000,
                                                new BigDecimal("0.0550")))));
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().paymentId()).isEqualTo(5);
        assertThat(result.data().expiresAt()).isEqualTo(Instant.parse("2026-11-16T01:10:00Z"));
        server.verify();
    }

    @Test
    @DisplayName("결제창은 주문 참조 경로에 본문 없이 요청하고 새 PG 주문번호를 읽는다")
    void checkoutUsesOrderRefAndReadsNewPgOrderId() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123/checkouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", "relay-test"))
                .andExpect(content().string(""))
                .andRespond(
                        withStatus(HttpStatus.CREATED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        """
                                        {"success":true,"data":{"pgOrderId":"ORDER-123-2","amount":5000,"expiresAt":"2026-11-16T10:10:00+09:00"},"error":null}
                                        """));

        var result = client.checkout("ORDER-123");

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .isEqualTo(
                        new Checkout("ORDER-123-2", 5000, Instant.parse("2026-11-16T01:10:00Z")));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVING", "UNKNOWN", "APPROVED", "CANCELED"})
    @DisplayName("조회는 숫자 결제ID 대신 주문 참조를 사용하고 Payment 상태·64비트 금액·시각을 보존한다")
    void lookupPreservesProviderFacts(String status) {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", "relay-test"))
                .andRespond(
                        withSuccess(
                                """
                                {"success":true,"data":{"paymentId":4000000005,"status":"%s","amount":3000000000,"refundedAmount":0,"pgOrderId":"ORDER-123-2","approvedAt":%s},"error":null}
                                """
                                        .formatted(
                                                status,
                                                "APPROVED".equals(status)
                                                        ? "\"2026-11-16T10:01:00+09:00\""
                                                        : "null"),
                                MediaType.APPLICATION_JSON));

        var result = client.get("ORDER-123");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .isEqualTo(
                        new Lookup(
                                4_000_000_005L,
                                status,
                                3_000_000_000L,
                                0,
                                "ORDER-123-2",
                                "APPROVED".equals(status)
                                        ? Instant.parse("2026-11-16T01:01:00Z")
                                        : null));
        server.verify();
    }

    @Test
    @DisplayName("승인은 인증 결과와 구매자만 보내고200 APPROVED를 읽으며 승인 시각을 추정하지 않는다")
    void approvedResponsePreservesPaymentIdentity() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123/approval"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", "relay-test"))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {"buyerId":"11111111-1111-4111-8111-111111111111","paymentKey":"authenticated-key","pgOrderId":"ORDER-123-2","amount":5000}
                                        """,
                                        JsonCompareMode.STRICT))
                .andRespond(
                        withSuccess(
                                """
                                {"success":true,"data":{"paymentId":5,"status":"APPROVED"},"error":null}
                                """,
                                MediaType.APPLICATION_JSON));

        var result =
                client.approve(
                        "ORDER-123",
                        new Approval(
                                "11111111-1111-4111-8111-111111111111",
                                "authenticated-key",
                                "ORDER-123-2",
                                5000));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(new ApprovalResult(5L, "APPROVED", null, null));
        server.verify();
    }

    @Test
    @DisplayName("기존 Mock Payment의 실행 응답을 새 결제 준비 성공으로 해석하지 않는다")
    void rejectsLegacyExecutionResponse() {
        server.expect(requestTo("http://payment-contract/v1/internal/payments/ORDER-123"))
                .andRespond(
                        withSuccess(
                                """
                                {"requestId":"legacy-request","paymentId":5,"status":"SUCCESS","amount":5000}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.get("ORDER-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Payment 응답 봉투가 올바르지 않습니다.");
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
