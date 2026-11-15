package com.shoppinglive.payment.service;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundMockEngineTest {
    @Test
    @DisplayName("실행 비활성 상태의 자동 엔진은 직접 예약돼도 환불을 실행하지 않는다")
    void disabledEngineNeverSubmitsAnExecution() {
        RefundExecutionService executions = mock(RefundExecutionService.class);
        RefundMockEngine engine = new RefundMockEngine(executions, false);
        try {
            engine.schedule(1);
            verifyNoInteractions(executions);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("활성 엔진은 커밋 후 예약한 환불을 실행한다")
    void enabledEngineRunsScheduledExecution() throws Exception {
        RefundExecutionService executions = mock(RefundExecutionService.class);
        var called = new java.util.concurrent.CountDownLatch(1);
        when(executions.execute(1))
                .thenAnswer(
                        i -> {
                            called.countDown();
                            return true;
                        });
        RefundMockEngine engine = new RefundMockEngine(executions, true);
        try {
            engine.schedule(1);
            org.assertj.core.api.Assertions.assertThat(
                            called.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            verify(executions, times(1)).execute(1);
        } finally {
            engine.shutdown();
        }
    }
}
