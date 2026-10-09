package com.shoppinglive.commerce.refunds.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RefundRetryPolicy {
    public static final int MAX_RETRIES = 3;
    public static final Duration RESULT_QUERY_DELAY = Duration.ofMinutes(1);

    /** The initial call uses retryCount=0; its next retry has a one-second jitter cap. */
    public Duration afterUnknown(int retryCount) {
        if (retryCount < 0 || retryCount > MAX_RETRIES) throw new IllegalArgumentException("retryCount must be 0..3");
        if (retryCount == MAX_RETRIES) return RESULT_QUERY_DELAY;
        long capMillis = 1000L << retryCount;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capMillis + 1));
    }
}
