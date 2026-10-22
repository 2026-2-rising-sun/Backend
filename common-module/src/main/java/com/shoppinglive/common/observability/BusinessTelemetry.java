package com.shoppinglive.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.transaction.support.*;

/** 관측 실패가 업무 결과를 바꾸지 않도록 하고, 확정 결과는 외부 트랜잭션 커밋까지 기다린다. */
public class BusinessTelemetry {
    private static final Logger log = LoggerFactory.getLogger(BusinessTelemetry.class);
    private final MeterRegistry registry;

    public BusinessTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void count(String metric, Tags labels, Map<String, ?> identifiers) {
        committed(() -> recordCount(metric, labels, identifiers));
    }

    /** 독립 트랜잭션의 커밋이 이미 끝난 시도를 기록한다. */
    public void committedAttempt(String metric, Tags labels, Map<String, ?> identifiers) {
        safely(() -> recordCount(metric, labels, identifiers));
    }

    private void recordCount(String metric, Tags labels, Map<String, ?> identifiers) {
        registry.counter(metric, labels).increment();
        var entry = log.atInfo().addKeyValue("event", metric);
        labels.forEach(tag -> entry.addKeyValue(tag.getKey(), tag.getValue()));
        identifiers.forEach(
                (key, value) -> {
                    if (MDC.get(key) == null) entry.addKeyValue(key, value);
                });
        entry.log("업무 처리 결과 확정");
    }

    public void duration(String metric, Tags labels, Map<String, ?> identifiers, long started) {
        long elapsed = System.nanoTime() - started;
        committed(
                () -> {
                    registry.timer(metric, labels).record(elapsed, TimeUnit.NANOSECONDS);
                    var entry =
                            log.atInfo()
                                    .addKeyValue("event", metric)
                                    .addKeyValue("durationMs", elapsed / 1_000_000);
                    labels.forEach(tag -> entry.addKeyValue(tag.getKey(), tag.getValue()));
                    identifiers.forEach(
                            (key, value) -> {
                                if (MDC.get(key) == null) entry.addKeyValue(key, value);
                            });
                    entry.log("업무 처리 관측");
                });
    }

    public void failure(String metric, Tags labels, Map<String, ?> identifiers) {
        safely(
                () -> {
                    registry.counter(metric, labels).increment();
                    var entry = log.atWarn().addKeyValue("event", metric);
                    labels.forEach(tag -> entry.addKeyValue(tag.getKey(), tag.getValue()));
                    identifiers.forEach(
                            (key, value) -> {
                                if (MDC.get(key) == null) entry.addKeyValue(key, value);
                            });
                    entry.log("업무 처리 실패");
                });
    }

    private void committed(Runnable action) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable captured =
                () -> {
                    Map<String, String> previous = MDC.getCopyOfContextMap();
                    try {
                        if (context == null) MDC.clear();
                        else MDC.setContextMap(context);
                        safely(action);
                    } finally {
                        if (previous == null) MDC.clear();
                        else MDC.setContextMap(previous);
                    }
                };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            captured.run();
                        }
                    });
        } else captured.run();
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("관측 기록 실패 type={}", failure.getClass().getSimpleName());
        }
    }
}
