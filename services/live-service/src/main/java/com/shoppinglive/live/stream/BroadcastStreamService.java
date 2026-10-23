package com.shoppinglive.live.stream;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.broadcast.service.ChatQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class BroadcastStreamService {
    private final BroadcastRepository broadcasts;
    private final ChatQueryService chats;
    private final BroadcastStreamRegistry registry;
    private final StreamProperties properties;

    public BroadcastStreamService(
            final BroadcastRepository broadcasts,
            final ChatQueryService chats,
            final BroadcastStreamRegistry registry,
            final StreamProperties properties) {
        this.broadcasts = broadcasts;
        this.chats = chats;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 먼저 등록하고 그 뒤에 방송 상태를 읽는다. 종료 알림이 등록 전에 처리되었더라도 뒤따르는 상태 읽기가 ENDED 를 보고 거절하므로 종료된 방송에 연결이 남지
     * 않는다. 놓친 채팅도 등록 뒤에 조회하므로 조회와 등록 사이에 쓰인 채팅이 빠지지 않는다. 겹치는 채팅은 클라이언트가 messageId 로 거른다.
     */
    public SseEmitter open(final long broadcastId, final String lastEventId) {
        final SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        final BroadcastConnection connection = registry.register(broadcastId, emitter);
        try {
            final Broadcast broadcast =
                    broadcasts
                            .findById(broadcastId)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
            if (broadcast.getStatus() != BroadcastStatus.LIVE) {
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
            }
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        try {
            connection.sendBeforeActivate(
                    StreamEvent.of("stream.ready", Map.of("broadcastId", broadcastId)));
        } catch (Exception e) {
            connection.close();
            throw new IllegalStateException("stream.ready 를 보내지 못했습니다.", e);
        }
        try {
            connection.activate(missedChats(broadcastId, lastEventId));
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return emitter;
    }

    /** 재전송은 대기 한도와 같은 개수까지만 보낸다. 그보다 많이 밀린 연결은 느린 연결과 같게 보고 최근 내역 조회에 맡긴다. */
    private List<StreamEvent> missedChats(final long broadcastId, final String lastEventId) {
        final Long lastMessageId = parseMessageId(lastEventId);
        if (lastMessageId == null) {
            return List.of();
        }
        return chats
                .missedSince(broadcastId, lastMessageId, Instant.now(), properties.queueCapacity())
                .stream()
                .map(
                        chat ->
                                new StreamEvent(
                                        "chat.created", String.valueOf(chat.messageId()), chat))
                .toList();
    }

    /** 채팅 이벤트만 id 를 가진다. 숫자가 아니면 이어받지 않는다. */
    private static Long parseMessageId(final String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(lastEventId.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
