package com.shoppinglive.payment.service;

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
    private final boolean enabled;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "mock-refund-engine");
                        thread.setDaemon(true);
                        return thread;
                    });

    public RefundMockEngine(RefundExecutionService executions, boolean enabled) {
        this(executions, enabled, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RefundMockEngine(
            RefundExecutionService executions,
            @org.springframework.beans.factory.annotation.Value(
                            "${payment.refunds.execution-enabled:false}")
                    boolean enabled,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.executions = executions;
        this.enabled = enabled;
        this.jdbc = jdbc;
    }

    public void schedule(long refundRequestId) {
        if (!enabled
                && (jdbc == null
                        || !Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM refund_execution WHERE id=? AND order_closure_request_id IS NOT NULL)",
                                        Boolean.class,
                                        refundRequestId)))) return;
        scheduler.submit(
                () -> {
                    try {
                        executions.execute(refundRequestId);
                    } catch (RuntimeException failure) {
                        log.warn(
                                "모의 환불 실행 실패: 환불 요청 ID={} 원인={}",
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
