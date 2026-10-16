package com.shoppinglive.live.viewer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ViewerPresenceServiceTest {
    final BroadcastRepository broadcasts = mock(BroadcastRepository.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ViewerPresenceService service = new ViewerPresenceService(broadcasts, redis);

    void broadcast(BroadcastStatus status) {
        Broadcast broadcast = mock(Broadcast.class);
        when(broadcast.getStatus()).thenReturn(status);
        when(broadcasts.findById(42L)).thenReturn(Optional.of(broadcast));
    }

    @Test
    @DisplayName("준비·종료 방송은 Redis 접근 없이 0을 반환하고 heartbeat를 거절한다")
    void inactiveBroadcastDoesNotCount() {
        for (var status :
                new BroadcastStatus[] {BroadcastStatus.PREPARING, BroadcastStatus.ENDED}) {
            broadcast(status);
            assertThat(service.total(42).total()).isZero();
            assertThatThrownBy(() -> service.heartbeat(42, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("없는 방송과 누락된 브라우저 식별자는 집계에 등록하지 않는다")
    void invalidBroadcastOrViewerIsRejected() {
        when(broadcasts.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.total(42)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.heartbeat(42, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("집계 장애는 시청자 0명으로 바꾸지 않고 오류를 반환한다")
    void unavailableRedisDoesNotReturnZero() {
        broadcast(BroadcastStatus.LIVE);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("테스트 장애"));
        assertThatThrownBy(() -> service.total(42))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("시청자 수를 확인하지 못했습니다");
    }
}
