package com.shoppinglive.common.web;

import java.util.UUID;
import org.slf4j.MDC;

public final class CorrelationId {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }
}
