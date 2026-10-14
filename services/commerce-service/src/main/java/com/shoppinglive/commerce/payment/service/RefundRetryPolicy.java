package com.shoppinglive.commerce.payment.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RefundRetryPolicy {
    public static final int MAX_RETRIES = 3;
    public static final Duration RESULT_QUERY_DELAY = Duration.ofMinutes(1);

    /** 최초 호출의 retryCount는 0이며, 다음 재시도 대기 시간은 최대 1초 범위에서 무작위로 정한다. */
    public Duration afterUnknown(int retryCount) {
        if (retryCount < 0 || retryCount > MAX_RETRIES) {
            throw new IllegalArgumentException("retryCount는 0~3이어야 합니다.");
        }
        if (retryCount == MAX_RETRIES) {
            return RESULT_QUERY_DELAY;
        }
        long capMillis = 1000L << retryCount;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capMillis + 1));
    }
}
