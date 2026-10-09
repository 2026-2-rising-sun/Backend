package com.shoppinglive.commerce.refunds.application;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Starts a refund execution after the intake transaction commits. */
@Component
public class RefundMockEngine {
    private static final Logger log = LoggerFactory.getLogger(RefundMockEngine.class);
    private final RefundExecutionService executions;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mock-refund-engine");
        thread.setDaemon(true);
        return thread;
    });

    public RefundMockEngine(RefundExecutionService executions) {
        this.executions = executions;
    }

    public void schedule(long refundRequestId) {
        scheduler.submit(() -> {
            try {
                executions.execute(refundRequestId);
            } catch (RuntimeException failure) {
                log.warn("mock refund execution failed for requestId={}: {}", refundRequestId, failure.toString());
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
