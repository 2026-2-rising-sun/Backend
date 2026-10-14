package com.shoppinglive.commerce.payment.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 환불 접수 트랜잭션이 커밋된 뒤 환불 실행을 시작한다. */
@Component
public class RefundMockEngine {
    private static final Logger log = LoggerFactory.getLogger(RefundMockEngine.class);
    private final RefundExecutionService executions;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "mock-refund-engine");
                        thread.setDaemon(true);
                        return thread;
                    });

    public RefundMockEngine(RefundExecutionService executions) {
        this.executions = executions;
    }

    public void schedule(long refundRequestId) {
        scheduler.submit(
                () -> {
                    try {
                        executions.execute(refundRequestId);
                    } catch (RuntimeException failure) {
                        log.warn(
                                "mock refund execution failed for requestId={}: {}",
                                refundRequestId,
                                failure.toString());
                    }
                });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
