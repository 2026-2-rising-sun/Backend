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

    @Test
    @DisplayName("비활성 엔진도 영속 검증된 보상 건만 실행하고 일반 환불은 대기한다")
    void disabledEngineRunsOnlyPersistedCompensation() throws Exception {
        var executions = mock(RefundExecutionService.class);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var called = new java.util.concurrent.CountDownLatch(1);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(1L))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(2L))).thenReturn(false);
        when(executions.execute(1L))
                .thenAnswer(
                        i -> {
                            called.countDown();
                            return true;
                        });
        var engine = new RefundMockEngine(executions, false, jdbc);
        try {
            engine.schedule(2L);
            engine.schedule(1L);
            org.assertj.core.api.Assertions.assertThat(
                            called.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            verify(executions, times(1)).execute(1L);
            verify(executions, never()).execute(2L);
        } finally {
            engine.shutdown();
        }
    }
}
