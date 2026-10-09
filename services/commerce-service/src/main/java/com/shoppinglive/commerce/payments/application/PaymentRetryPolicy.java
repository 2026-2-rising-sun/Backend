package com.shoppinglive.commerce.payments.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class PaymentRetryPolicy {
    public static final int MAX_RETRIES=3;
    public static final Duration RESULT_QUERY_DELAY=Duration.ofMinutes(1);
    public Duration afterUnknown(int retryCount) {
        if(retryCount<0 || retryCount>MAX_RETRIES)throw new IllegalArgumentException("retry count must be 0..3");
        if(retryCount==MAX_RETRIES)return RESULT_QUERY_DELAY;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong((1000L<<retryCount)+1));
    }
}
