package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.broadcast.service.ChatQueryService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class BroadcastStreamServiceTest {
    @Test
    @DisplayName("놓친 채팅 조회가 실패하면 등록한 SSE 연결을 제거한다")
    void closesConnectionWhenReplayFails() {
        final var properties =
                new StreamProperties(Duration.ofSeconds(15), Duration.ofMinutes(30), 256, 5);
        final var registry = new BroadcastStreamRegistry(properties);
        try {
            final var broadcasts = mock(BroadcastRepository.class);
            final var chats = mock(ChatQueryService.class);
            final var broadcast = mock(Broadcast.class);
            when(broadcast.getStatus()).thenReturn(BroadcastStatus.LIVE);
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast));
            final var failure = new DataAccessResourceFailureException("채팅 DB 연결 실패");
            when(chats.missedSince(eq(1L), eq(10L), any(), anyInt())).thenThrow(failure);
            final var service = new BroadcastStreamService(broadcasts, chats, registry, properties);

            assertThatThrownBy(() -> service.open(1L, "10")).isSameAs(failure);
            assertThat(registry.connectionCount()).isZero();
        } finally {
            registry.shutdown();
        }
    }
}
