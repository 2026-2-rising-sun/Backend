package com.shoppinglive.notification.inbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/** 스케줄러가 기준 시각을 어떻게 정하고, 실패를 어떻게 다루는지 확인한다. 지우는 SQL 은 저장소 테스트가 본다. */
@DisplayName("알림 보관 기간 스케줄러")
class NotificationRetentionSchedulerTest {

    @Test
    @DisplayName("보관 기간을 설정으로 바꾸면 지우는 기준 시각도 따라 바뀐다")
    void cutoffFollowsConfiguredRetention() {
        NotificationWriteRepository notifications = mock(NotificationWriteRepository.class);
        Instant before = Instant.now().minus(Duration.ofDays(7));

        new NotificationRetentionScheduler(notifications, Duration.ofDays(7)).run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(notifications).deleteCreatedBefore(cutoff.capture(), anyInt());
        // 호출 직전에 계산한 값이므로 몇 밀리초 차이는 둔다.
        assertThat(cutoff.getValue()).isBetween(before, before.plusSeconds(10));
    }

    @Test
    @DisplayName("한 주기가 실패해도 던지지 않는다 — 던지면 다음 주기가 돌지 않는다")
    void swallowsFailureSoNextSweepRuns() {
        NotificationWriteRepository failing = mock(NotificationWriteRepository.class);
        when(failing.deleteCreatedBefore(any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        NotificationRetentionScheduler scheduler =
                new NotificationRetentionScheduler(failing, Duration.ofDays(30));

        assertThatCode(scheduler::run).doesNotThrowAnyException();

        // 다음 주기에 다시 시도한다. 예외를 삼키지 않으면 Spring 이 이 작업을 더 돌리지 않는다.
        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }
}
