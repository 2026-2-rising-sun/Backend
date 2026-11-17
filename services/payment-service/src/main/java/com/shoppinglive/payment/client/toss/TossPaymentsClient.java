package com.shoppinglive.payment.client.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String IDEMPOTENT_REQUEST_PROCESSING_CODE =
            "IDEMPOTENT_REQUEST_PROCESSING";

    private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);
    private static final Set<String> OUR_KEY_BAD_REQUEST_CODES = Set.of("INVALID_API_KEY");

    // 본문 읽기가 멈춰도 데드라인에 끝내려고 호출을 별도 가상 스레드에서 실행한다.
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    /** null이면 시크릿 키가 없어 토스를 호출하지 않는다. */
    private final RestClient restClient;

    private final Duration responseDeadline;

    /** responseDeadline은 헤더와 본문을 합친 호출 전체 대기 한도다. 넘으면 Unknown이다. */
    public TossPaymentsClient(RestClient restClient, Duration responseDeadline) {
        this.restClient = restClient;
        this.responseDeadline = responseDeadline;
    }

    /** 시크릿 키 없이 기동했을 때 쓴다. 모든 호출이 요청을 보내지 않고 NotSent다. */
    static TossPaymentsClient withoutSecretKey() {
        return new TossPaymentsClient(null, Duration.ZERO);
    }

    /** orderId·amount는 요청 값이 아닌 DB에 저장한 값을 넘긴다. */
    public TossResult confirm(
            String paymentKey, String orderId, long amount, String idempotencyKey) {
        return call(
                client ->
                        client.post()
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
        return call(
                client -> client.get().uri("/v1/payments/{paymentKey}", paymentKey),
                status -> true);
    }

    /** 금액을 보내지 않아 전액 취소한다. */
    public TossResult cancel(String paymentKey, String cancelReason, String idempotencyKey) {
        return call(
                client ->
                        client.post()
                                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                                .header(IDEMPOTENCY_KEY, idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("cancelReason", cancelReason)),
                "CANCELED"::equals);
    }

    private TossResult call(
            Function<RestClient, RestClient.RequestHeadersSpec<?>> request,
            Predicate<String> isExpectedStatus) {
        if (restClient == null) {
            return new TossResult.NotSent("payment.toss.secret-key 미설정");
        }
        Future<TossResult> call = CALLS.submit(() -> send(request, isExpectedStatus));
        try {
            return call.get(responseDeadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            call.cancel(true);
            return new TossResult.Unknown("응답 데드라인 초과");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return new TossResult.Unknown("응답 대기 중 인터럽트");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            return new TossResult.Unknown(
                    "호출 예외: " + exception.getCause().getClass().getSimpleName());
        }
    }

    private TossResult send(
            Function<RestClient, RestClient.RequestHeadersSpec<?>> request,
            Predicate<String> isExpectedStatus) {
        try {
            return request.apply(restClient)
                    .exchange((ignored, response) -> classify(response, isExpectedStatus));
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
        if (statusCode == 401) {
            return rejectedByOurConfiguration("HTTP 401");
        }
        if (statusCode == 429) {
            return new TossResult.NotSent("HTTP 429");
        }
        TossError error = readOrNull(response, TossError.class);
        if (error == null || error.code() == null || error.code().isBlank()) {
            return new TossResult.Unknown("HTTP " + statusCode + " 오류 code를 읽지 못함");
        }
        String code = error.code();
        if ((statusCode == 400 && OUR_KEY_BAD_REQUEST_CODES.contains(code))
                || (statusCode == 403 && OUR_CONFIGURATION_FORBIDDEN_CODES.contains(code))) {
            return rejectedByOurConfiguration("HTTP " + statusCode + " " + code);
        }
        if ((statusCode == 400 && POSSIBLY_IN_FLIGHT_BAD_REQUEST_CODES.contains(code))
                || (statusCode == 403 && CONSECUTIVE_REQUEST_FORBIDDEN_CODE.equals(code))
                || (statusCode == 409 && IDEMPOTENT_REQUEST_PROCESSING_CODE.equals(code))) {
            return new TossResult.Unknown("HTTP " + statusCode + " " + code);
        }
        return new TossResult.Declined(code, error.message());
    }

    /** 결제는 처음 상태로 돌아가 응답만으로는 원인이 보이지 않으므로 운영자가 키 설정을 확인하게 남긴다. 키 값은 남기지 않는다. */
    private static TossResult rejectedByOurConfiguration(String reason) {
        log.error(
                "토스가 우리 키·설정 오류로 거부했다({}). PAYMENT_TOSS_SECRET_KEY가 API 개별 연동 시크릿 키(test_sk_·live_sk_)인지 확인한다.",
                reason);
        return new TossResult.NotSent(reason);
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
