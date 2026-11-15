package com.shoppinglive.payment.client.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/** 토스 승인·조회·취소를 호출하고 응답을 TossResult로 분류한다. 예외를 던지지 않는다. */
public class TossPaymentsClient {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Set<String> OUR_CONFIGURATION_FORBIDDEN_CODES =
            Set.of("INCORRECT_BASIC_AUTH_FORMAT", "API_KEY_ACCESS_DENIED");
    private static final Set<String> POSSIBLY_IN_FLIGHT_BAD_REQUEST_CODES =
            Set.of("ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST");
    private static final String CONSECUTIVE_REQUEST_FORBIDDEN_CODE =
            "FORBIDDEN_CONSECUTIVE_REQUEST";

    private final RestClient restClient;

    public TossPaymentsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** orderId·amount는 요청 값이 아닌 DB에 저장한 값을 넘긴다. */
    public TossResult confirm(
            String paymentKey, String orderId, long amount, String idempotencyKey) {
        return call(
                restClient
                        .post()
                        .uri("/v1/payments/confirm")
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "paymentKey",
                                        paymentKey,
                                        "orderId",
                                        orderId,
                                        "amount",
                                        amount)),
                "DONE"::equals);
    }

    /** 상태 판단은 호출자가 한다. 본문이 읽히면 어떤 status든 Success다. */
    public TossResult getPayment(String paymentKey) {
        return call(restClient.get().uri("/v1/payments/{paymentKey}", paymentKey), status -> true);
    }

    /** 금액을 보내지 않아 전액 취소한다. */
    public TossResult cancel(String paymentKey, String cancelReason, String idempotencyKey) {
        return call(
                restClient
                        .post()
                        .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("cancelReason", cancelReason)),
                "CANCELED"::equals);
    }

    private TossResult call(
            RestClient.RequestHeadersSpec<?> request, Predicate<String> isExpectedStatus) {
        try {
            return request.exchange((ignored, response) -> classify(response, isExpectedStatus));
        } catch (RuntimeException exception) {
            return classify(exception);
        }
    }

    /** 요청을 보내기 전에 실패한 경우만 NotSent다. 그 밖의 예외는 처리 여부를 모른다. */
    private static TossResult classify(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new TossResult.NotSent("연결 실패: " + cause.getClass().getSimpleName());
            }
        }
        return new TossResult.Unknown("호출 예외: " + exception.getClass().getSimpleName());
    }

    private static TossResult classify(
            ConvertibleClientHttpResponse response, Predicate<String> isExpectedStatus)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        int statusCode = status.value();
        if (statusCode == 200) {
            TossPayment payment = readOrNull(response, TossPayment.class);
            if (payment != null
                    && payment.status() != null
                    && isExpectedStatus.test(payment.status())) {
                return new TossResult.Success(payment);
            }
            return new TossResult.Unknown(
                    "HTTP 200 결제 상태 불일치: " + (payment == null ? "본문 파싱 실패" : payment.status()));
        }
        if (status.is5xxServerError()) {
            return new TossResult.Unknown("HTTP " + statusCode);
        }
        if (!status.is4xxClientError()) {
            return new TossResult.Unknown("분류하지 못한 HTTP " + statusCode);
        }
        TossError error = readOrNull(response, TossError.class);
        if (error == null || error.code() == null || error.code().isBlank()) {
            return new TossResult.Unknown("HTTP " + statusCode + " 오류 code를 읽지 못함");
        }
        String code = error.code();
        if (statusCode == 401
                || (statusCode == 403 && OUR_CONFIGURATION_FORBIDDEN_CODES.contains(code))
                || statusCode == 429) {
            return new TossResult.NotSent("HTTP " + statusCode + " " + code);
        }
        if ((statusCode == 400 && POSSIBLY_IN_FLIGHT_BAD_REQUEST_CODES.contains(code))
                || (statusCode == 403 && CONSECUTIVE_REQUEST_FORBIDDEN_CODE.equals(code))) {
            return new TossResult.Unknown("HTTP " + statusCode + " " + code);
        }
        return new TossResult.Declined(code, error.message());
    }

    /** 본문이 비었거나 JSON으로 읽지 못하거나 읽는 중 끊기면 null이다. */
    private static <T> T readOrNull(ConvertibleClientHttpResponse response, Class<T> type) {
        try {
            return response.bodyTo(type);
        } catch (RestClientException exception) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossError(String code, String message) {}
}
