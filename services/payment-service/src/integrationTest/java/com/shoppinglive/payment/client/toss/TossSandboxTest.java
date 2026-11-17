package com.shoppinglive.payment.client.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실제 토스 API 응답이 TossPaymentsClient 분류와 맞는지 본다. 결제창 없이 만들 수 있는 응답만 다룬다.
 *
 * <p>외부 호출이라 {@code toss-sandbox} 태그로 test·integrationTest·CI에서 빼고 {@code tossSandboxTest}로만 실행한다.
 * 주소는 고정한다. .env에서 가짜 토스 주소를 켜 둔 채 실행해도 실제 토스를 부른다.
 */
@Tag("toss-sandbox")
class TossSandboxTest {
    private static final String TOSS = "https://api.tosspayments.com";

    @Test
    @DisplayName("PAY-PG-01 틀린 시크릿 키로 승인하면 토스가 401로 답하고 보내지 않음이다")
    void wrongSecretKeyIsNotSent() {
        TossResult result =
                client("test_sk_" + UUID.randomUUID().toString().replace("-", ""))
                        .confirm("tsandbox-" + UUID.randomUUID(), "OD-SANDBOX-1", 1000, key());

        System.out.println("[토스 sandbox] 틀린 키 승인: " + result);
        // 401까지 본다. 네트워크가 막혀도 연결 실패로 NotSent(httpStatus=null)가 된다
        assertThat(result).isInstanceOf(TossResult.NotSent.class);
        assertThat(result.httpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("PAY-PG-02 테스트 키로 없는 paymentKey를 승인하면 거절이다")
    void confirmUnknownPaymentKeyIsDeclined() {
        TossResult result =
                testKeyClient()
                        .confirm("tsandbox-" + UUID.randomUUID(), "OD-SANDBOX-1", 1000, key());

        System.out.println("[토스 sandbox] 없는 paymentKey 승인: " + result);
        assertThat(result).isInstanceOf(TossResult.Declined.class);
    }

    @Test
    @DisplayName("PAY-PG-02 테스트 키로 없는 paymentKey를 조회하면 거절이다")
    void getUnknownPaymentKeyIsDeclined() {
        TossResult result = testKeyClient().getPayment("tsandbox-" + UUID.randomUUID());

        System.out.println("[토스 sandbox] 없는 paymentKey 조회: " + result);
        assertThat(result).isInstanceOf(TossResult.Declined.class);
    }

    private static TossPaymentsClient testKeyClient() {
        String secretKey = System.getenv("PAYMENT_TOSS_SECRET_KEY");
        // 운영 키(live_sk_)로 실제 결제 API를 부르지 않는다
        assumeTrue(
                secretKey != null && secretKey.startsWith("test_sk_"),
                "PAYMENT_TOSS_SECRET_KEY에 test_sk_ 키가 필요하다");
        return client(secretKey);
    }

    private static TossPaymentsClient client(String secretKey) {
        return new TossClientConfiguration()
                .tossPaymentsClient(
                        new TossProperties(
                                TOSS, secretKey, Duration.ofSeconds(3), Duration.ofSeconds(10)));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
