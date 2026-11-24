package com.shoppinglive.payment.client.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentsClientTest {
    static final String BASE = "https://toss.test";
    static final String SECRET = "test_sk_unit_secret";
    static final String BASIC =
            "Basic "
                    + Base64.getEncoder()
                            .encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));

    MockRestServiceServer server;
    TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
                TossClientConfiguration.configure(
                        RestClient.builder(),
                        new TossProperties(
                                BASE, SECRET, Duration.ofSeconds(3), Duration.ofSeconds(60)));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder.build(), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("PAY-PG-04 승인은 Basic 인증·Idempotency-Key·DB 값 본문으로 confirm을 호출하고 DONE이면 Success다")
    void confirmSendsAuthIdempotencyKeyAndBody() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header("Idempotency-Key", "confirm-pg-order-1"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(
                        content()
                                .json(
                                        "{\"paymentKey\":\"pk-1\",\"orderId\":\"pg-order-1\",\"amount\":15000}",
                                        true))
                .andRespond(
                        withSuccess(
                                payment("DONE", ",\"unknownField\":{\"x\":1}"),
                                MediaType.APPLICATION_JSON));

        TossResult result = client.confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

        assertThat(result).isInstanceOf(TossResult.Success.class);
        TossPayment payment = ((TossResult.Success) result).payment();
        assertThat(payment.paymentKey()).isEqualTo("pk-1");
        assertThat(payment.orderId()).isEqualTo("pg-order-1");
        assertThat(payment.status()).isEqualTo("DONE");
        assertThat(payment.totalAmount()).isEqualTo(15000);
        assertThat(payment.approvedAt().toInstant())
                .isEqualTo(Instant.parse("2026-11-15T01:00:00Z"));
        assertThat(payment.partialCancelable()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("PAY-PG-04 취소는 Basic 인증·Idempotency-Key·취소 사유로 호출하고 CANCELED면 Success다")
    void cancelSendsAuthIdempotencyKeyAndReason() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header("Idempotency-Key", "cancel-pg-order-1"))
                .andExpect(content().json("{\"cancelReason\":\"승인 검증 실패\"}", true))
                .andRespond(withSuccess(payment("CANCELED", ""), MediaType.APPLICATION_JSON));

        TossResult result = client.cancel("pk-1", "승인 검증 실패", "cancel-pg-order-1");

        assertThat(result).isInstanceOf(TossResult.Success.class);
        server.verify();
    }

    @Test
    @DisplayName("[PAY-PG-03][REF-EXEC-06] JSON code가 있는 HTTP 408은 승인·취소 모두 처리 여부를 모르는 Unknown이다")
    void requestTimeoutWithCodeIsUnknown() {
        String body = "{\"code\":\"REQUEST_TIMEOUT\",\"message\":\"시간 초과\"}";
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withStatus(org.springframework.http.HttpStatus.REQUEST_TIMEOUT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body));
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withStatus(org.springframework.http.HttpStatus.REQUEST_TIMEOUT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body));

        TossResult confirm = client.confirm("pk-1", "o-1", 1000, "key-1");
        TossResult cancel = client.cancel("pk-1", "고객 환불", "r-1", 1000L);

        assertThat(confirm).isInstanceOf(TossResult.Unknown.class);
        assertThat(cancel).isInstanceOf(TossResult.Unknown.class);
        assertThat(confirm.httpStatus()).isEqualTo(408);
        server.verify();
    }

    @Test
    @DisplayName("[REF-EXEC-04] 조회 응답의 취소 내역(거래 키·사유·금액·상태)을 읽고, 내역이 없으면 빈 목록이다")
    void readsCancels() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1"))
                .andRespond(
                        withSuccess(
                                """
                                {"paymentKey":"pk-1","orderId":"o-1","status":"PARTIAL_CANCELED",
                                 "totalAmount":30000,"isPartialCancelable":true,
                                 "cancels":[{"transactionKey":"tx-1","cancelReason":"고객 환불 r-1",
                                 "cancelAmount":4000,"cancelStatus":"DONE","refundableAmount":26000}]}
                                """,
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/pk-2"))
                .andRespond(
                        withSuccess(
                                """
                                {"paymentKey":"pk-2","orderId":"o-2","status":"DONE",
                                 "totalAmount":30000,"isPartialCancelable":true,"cancels":null}
                                """,
                                MediaType.APPLICATION_JSON));

        TossPayment canceled = ((TossResult.Success) client.getPayment("pk-1")).payment();
        TossPayment done = ((TossResult.Success) client.getPayment("pk-2")).payment();

        assertThat(canceled.partialCancelable()).isTrue();
        assertThat(canceled.cancels())
                .containsExactly(new TossPayment.Cancel("tx-1", "고객 환불 r-1", 4000, "DONE"));
        assertThat(done.cancels()).isEmpty();
    }

    @Test
    @DisplayName(
            "[REF-EXEC-02] 부분 취소는 cancelAmount를 보내고 CANCELED·PARTIAL_CANCELED면 Success, 금액이 없으면 전액 취소 본문 그대로다")
    void partialCancelSendsAmount() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andExpect(header("Idempotency-Key", "r-1"))
                .andExpect(
                        content().json("{\"cancelReason\":\"고객 환불\",\"cancelAmount\":4000}", true))
                .andRespond(
                        withSuccess(payment("PARTIAL_CANCELED", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andExpect(
                        content().json("{\"cancelReason\":\"고객 환불\",\"cancelAmount\":11000}", true))
                .andRespond(withSuccess(payment("CANCELED", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andExpect(content().json("{\"cancelReason\":\"보상\"}", true))
                .andRespond(
                        withSuccess(payment("PARTIAL_CANCELED", ""), MediaType.APPLICATION_JSON));

        assertThat(client.cancel("pk-1", "고객 환불", "r-1", 4000L))
                .isInstanceOf(TossResult.Success.class);
        assertThat(client.cancel("pk-1", "고객 환불", "r-2", 11000L))
                .isInstanceOf(TossResult.Success.class);
        // 전액 취소를 요청했는데 부분 취소 상태면 결과를 모른다
        assertThat(client.cancel("pk-1", "보상", "r-3", null)).isInstanceOf(TossResult.Unknown.class);
        server.verify();
    }

    @Test
    @DisplayName("PAY-PG-04 조회는 Basic 인증으로 호출하고 Idempotency-Key 없이 어떤 상태든 Success로 돌려준다")
    void getPaymentSendsAuthWithoutIdempotencyKey() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess(payment("ABORTED", ""), MediaType.APPLICATION_JSON));

        TossResult result = client.getPayment("pk-1");

        assertThat(result).isInstanceOf(TossResult.Success.class);
        assertThat(((TossResult.Success) result).payment().status()).isEqualTo("ABORTED");
        server.verify();
    }

    @Test
    @DisplayName("PAY-PG-01 연결 타임아웃은 NotSent")
    void connectTimeoutIsNotSent() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(
                        withException(new HttpConnectTimeoutException("HTTP connect timed out")));

        assertThat(confirm()).isInstanceOf(TossResult.NotSent.class);
    }

    @Test
    @DisplayName("PAY-PG-01 401은 NotSent이고 결과에 비밀 키가 없다")
    void unauthorizedIsNotSent() {
        respond(HttpStatus.UNAUTHORIZED, error("UNAUTHORIZED_KEY"));

        TossResult result = confirm();

        assertThat(result).isInstanceOf(TossResult.NotSent.class);
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "403, INCORRECT_BASIC_AUTH_FORMAT",
        "403, API_KEY_ACCESS_DENIED",
        "400, INVALID_API_KEY"
    })
    @DisplayName("PAY-PG-01 우리 키·설정 문제인 400·403은 NotSent")
    void rejectedByOurConfigurationIsNotSent(int status, String code) {
        respond(HttpStatus.valueOf(status), error(code));

        assertThat(confirm()).isInstanceOf(TossResult.NotSent.class);
    }

    @Test
    @DisplayName("PAY-PG-01 401은 본문 code를 읽지 못해도 NotSent")
    void unauthorizedWithoutCodeIsNotSent() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(confirm()).isInstanceOf(TossResult.NotSent.class);
    }

    @Test
    @DisplayName("PAY-PG-01 429는 토스가 처리했는지 모르므로 본문과 무관하게 Unknown(#398)")
    void tooManyRequestsIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "403, REJECT_CARD_COMPANY",
        "403, REJECT_CARD_PAYMENT",
        "403, FDS_ERROR",
        "400, INVALID_CARD_NUMBER",
        "404, NOT_FOUND_PAYMENT"
    })
    @DisplayName("PAY-PG-01·02 카드 거절 403과 그 밖의 4xx는 Declined(code, message)")
    void otherClientErrorsAreDeclined(int status, String code) {
        respond(HttpStatus.valueOf(status), error(code));

        assertThat(confirm()).isEqualTo(new TossResult.Declined(code, "메시지 " + code, status));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "400, ALREADY_PROCESSED_PAYMENT",
        "400, ALREADY_PROCESSING_REQUEST",
        "403, FORBIDDEN_CONSECUTIVE_REQUEST",
        "409, IDEMPOTENT_REQUEST_PROCESSING"
    })
    @DisplayName("PAY-PG-02 앞선 요청이 처리 중일 수 있는 4xx는 Unknown")
    void possiblyInFlightClientErrorsAreUnknown(int status, String code) {
        respond(HttpStatus.valueOf(status), error(code));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 5xx는 본문 code와 관계없이 Unknown")
    void serverErrorIsUnknown() {
        respond(HttpStatus.INTERNAL_SERVER_ERROR, error("FAILED_INTERNAL_SYSTEM_PROCESSING"));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 4xx인데 본문에서 code를 읽지 못하면 Unknown")
    void clientErrorWithoutCodeIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(
                        withStatus(HttpStatus.FORBIDDEN)
                                .contentType(MediaType.TEXT_HTML)
                                .body("<html>"));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 분류하지 못한 예외는 Unknown")
    void unclassifiedExceptionIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withException(new IOException("Connection reset")));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 승인 200인데 status가 DONE이 아니면 Unknown")
    void confirmNotDoneIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess(payment("IN_PROGRESS", ""), MediaType.APPLICATION_JSON));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 승인 200인데 본문을 파싱하지 못하면 Unknown")
    void confirmUnparsableBodyIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("{\"paymentKey\":", MediaType.APPLICATION_JSON));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-03 취소 200인데 status가 CANCELED가 아니면 Unknown")
    void cancelNotCanceledIsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andRespond(withSuccess(payment("DONE", ""), MediaType.APPLICATION_JSON));

        assertThat(client.cancel("pk-1", "사유", "cancel-pg-order-1"))
                .isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-PG-02·03 취소의 4xx는 Declined, 5xx는 Unknown")
    void cancelClientAndServerErrors() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(error("ALREADY_CANCELED_PAYMENT")));
        server.expect(requestTo(BASE + "/v1/payments/pk-1/cancel"))
                .andRespond(
                        withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(error("FAILED_REFUND_PROCESS")));

        assertThat(client.cancel("pk-1", "사유", "cancel-pg-order-1"))
                .isEqualTo(
                        new TossResult.Declined(
                                "ALREADY_CANCELED_PAYMENT", "메시지 ALREADY_CANCELED_PAYMENT", 400));
        assertThat(client.cancel("pk-1", "사유", "cancel-pg-order-1"))
                .isInstanceOf(TossResult.Unknown.class);
        server.verify();
    }

    @Test
    @DisplayName("PAY-PG-02·03 조회의 4xx는 Declined, 5xx는 Unknown")
    void getPaymentClientAndServerErrors() {
        server.expect(requestTo(BASE + "/v1/payments/pk-1"))
                .andRespond(
                        withStatus(HttpStatus.NOT_FOUND)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(error("NOT_FOUND_PAYMENT")));
        server.expect(requestTo(BASE + "/v1/payments/pk-1"))
                .andRespond(
                        withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(error("FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING")));

        assertThat(client.getPayment("pk-1"))
                .isEqualTo(
                        new TossResult.Declined("NOT_FOUND_PAYMENT", "메시지 NOT_FOUND_PAYMENT", 404));
        assertThat(client.getPayment("pk-1")).isInstanceOf(TossResult.Unknown.class);
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"201", "302"})
    @DisplayName("PAY-PG-03 200이 아닌 2xx와 3xx는 Unknown")
    void unexpectedStatusIsUnknown(int status) {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(
                        withStatus(HttpStatus.valueOf(status))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(payment("DONE", "")));

        assertThat(confirm()).isInstanceOf(TossResult.Unknown.class);
    }

    @Test
    @DisplayName("PAY-CONFIRM-12 토스 응답을 받으면 그 HTTP 상태를 결과에 담는다(분류는 그대로)")
    void resultCarriesHttpStatusOfReceivedResponse() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess(payment("DONE", ""), MediaType.APPLICATION_JSON));
        respond(HttpStatus.FORBIDDEN, error("REJECT_CARD_PAYMENT"));
        respond(HttpStatus.INTERNAL_SERVER_ERROR, error("FAILED_INTERNAL_SYSTEM_PROCESSING"));
        respond(HttpStatus.UNAUTHORIZED, error("UNAUTHORIZED_KEY"));
        respond(HttpStatus.CONFLICT, error("IDEMPOTENT_REQUEST_PROCESSING"));
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess(payment("IN_PROGRESS", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThat(confirm())
                .isInstanceOf(TossResult.Success.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(200);
        assertThat(confirm())
                .isInstanceOf(TossResult.Declined.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(403);
        assertThat(confirm())
                .isInstanceOf(TossResult.Unknown.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(500);
        assertThat(confirm())
                .isInstanceOf(TossResult.NotSent.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(401);
        assertThat(confirm())
                .isInstanceOf(TossResult.Unknown.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(409);
        assertThat(confirm())
                .isInstanceOf(TossResult.Unknown.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(200);
        assertThat(confirm())
                .as("본문은 다 받았고 JSON이 아님")
                .isInstanceOf(TossResult.Unknown.class)
                .extracting(TossResult::httpStatus)
                .isEqualTo(200);
        server.verify();
    }

    @Test
    @DisplayName("PAY-CONFIRM-12 응답을 받지 못한 예외(연결 실패·끊김)는 HTTP 상태가 없다")
    void exceptionsHaveNoHttpStatus() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(
                        withException(new HttpConnectTimeoutException("HTTP connect timed out")));
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withException(new IOException("Connection reset")));

        assertThat(confirm())
                .isInstanceOf(TossResult.NotSent.class)
                .extracting(TossResult::httpStatus)
                .isNull();
        assertThat(confirm())
                .isInstanceOf(TossResult.Unknown.class)
                .extracting(TossResult::httpStatus)
                .isNull();
    }

    private TossResult confirm() {
        return client.confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");
    }

    private void respond(HttpStatus status, String body) {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"메시지 " + code + "\"}";
    }

    private static String payment(String status, String extraFields) {
        return "{\"paymentKey\":\"pk-1\",\"orderId\":\"pg-order-1\",\"status\":\""
                + status
                + "\",\"totalAmount\":15000,\"approvedAt\":\"2026-11-15T10:00:00+09:00\""
                + ",\"isPartialCancelable\":true"
                + extraFields
                + "}";
    }
}
