package com.shoppinglive.notification.inbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/** 스케줄러가 기준 시각을 어떻게 정하고, 실패를 어떻게 다루는지 확인한다. 지우는 SQL 은 저장소 테스트가 본다. */
@DisplayName("알림 보관 기간 스케줄러")
class NotificationRetentionSchedulerTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("보관 기간이 0 이하이면 알림 삭제를 시작하지 않고 설정 오류를 알린다")
    void rejectsNonPositiveRetention(long days) {
        assertThatThrownBy(
                        () ->
                                new NotificationRetentionScheduler(
                                        mock(NotificationWriteRepository.class),
                                        Duration.ofDays(days)))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
    @DisplayName("삭제 실패를 처리하고 다음 호출에서 다시 시도한다")
    void swallowsFailureSoNextSweepRuns() {
        NotificationWriteRepository failing = mock(NotificationWriteRepository.class);
        when(failing.deleteCreatedBefore(any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        NotificationRetentionScheduler scheduler =
                new NotificationRetentionScheduler(failing, Duration.ofDays(30));

        assertThatCode(scheduler::run).doesNotThrowAnyException();

        // 다음 예약 실행에서 같은 삭제 작업을 다시 시도할 수 있다.
        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }
}
