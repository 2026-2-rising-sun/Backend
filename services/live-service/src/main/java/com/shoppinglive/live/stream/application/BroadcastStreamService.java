package com.shoppinglive.live.stream.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class BroadcastStreamService {
    private final BroadcastRepository broadcasts;
    private final BroadcastStreamRegistry registry;
    private final StreamProperties properties;

    public BroadcastStreamService(final BroadcastRepository broadcasts, final BroadcastStreamRegistry registry,
                                  final StreamProperties properties) {
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 먼저 등록하고 그 뒤에 방송 상태를 읽는다. 종료 알림이 등록 전에 처리되었더라도
     * 뒤따르는 상태 읽기가 ENDED 를 보고 거절하므로 종료된 방송에 연결이 남지 않는다.
     */
    public SseEmitter open(final long broadcastId) {
        final SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        final BroadcastConnection connection = registry.register(broadcastId, emitter);
        try {
            final Broadcast broadcast = broadcasts.findById(broadcastId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
            if (broadcast.getStatus() != BroadcastStatus.LIVE) {
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
            }
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        connection.activate(StreamEvent.of("stream.ready", Map.of("broadcastId", broadcastId)));
        return emitter;
    }
}
