package com.shoppinglive.live.broadcast.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.dto.ChatMessageResponse;
import com.shoppinglive.live.broadcast.entity.BroadcastChat;
import com.shoppinglive.live.broadcast.repository.BroadcastChatRepository;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatQueryService {
    private static final Limit RECENT = Limit.of(50);

    /** 로컬 측정에서 늦게 도착한 채팅은 최대 68ms 늦었다. 그보다 넉넉히 잡는다. */
    private static final Duration REPLAY_OVERLAP = Duration.ofSeconds(5);

    /** Ably 의 연결 이어받기 기준(약 2분)을 따른다. */
    private static final Duration REPLAY_WINDOW = Duration.ofMinutes(2);

    private final BroadcastRepository broadcasts;
    private final BroadcastChatRepository chats;

    public ChatQueryService(
            final BroadcastRepository broadcasts, final BroadcastChatRepository chats) {
        this.broadcasts = broadcasts;
        this.chats = chats;
    }

    /** 최신 50건을 고른 뒤 오래된 순서로 돌려준다. 같은 시각은 id 로 순서를 고정한다. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> recent(final long broadcastId) {
        if (!broadcasts.existsById(broadcastId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        }
        return chats
                .findByBroadcastIdOrderByCreatedAtDescIdDesc(broadcastId, RECENT)
                .reversed()
                .stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    /**
     * 재연결한 시청자가 놓쳤을 수 있는 채팅을 오래된 순서로 돌려준다. 마지막으로 받은 채팅보다 OVERLAP 앞부터 다시 준다. PostgreSQL 은 커밋 전에 번호를
     * 매기므로 마지막 번호보다 작은 채팅이 늦게 커밋되어 아직 못 받았을 수 있다. 겹친 채팅은 클라이언트가 messageId 로 거른다. 마지막 채팅이 WINDOW 보다
     * 오래됐거나 찾을 수 없으면 이어받지 않는다. 클라이언트는 최근 내역 조회로 복구한다.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> missedSince(
            final long broadcastId, final long lastMessageId, final Instant now, final int max) {
        return chats.findByIdAndBroadcastId(lastMessageId, broadcastId)
                .map(BroadcastChat::getCreatedAt)
                .filter(lastAt -> !lastAt.isBefore(now.minus(REPLAY_WINDOW)))
                .map(lastAt -> lastAt.minus(REPLAY_OVERLAP))
                .map(
                        from ->
                                chats
                                        .findByBroadcastIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                                                broadcastId, from, Limit.of(max))
                                        .reversed()
                                        .stream()
                                        .map(ChatMessageResponse::from)
                                        .toList())
                .orElse(List.of());
    }
}
