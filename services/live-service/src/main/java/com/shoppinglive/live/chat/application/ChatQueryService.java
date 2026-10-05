package com.shoppinglive.live.chat.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.chat.api.ChatMessageResponse;
import com.shoppinglive.live.chat.infrastructure.BroadcastChatRepository;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatQueryService {
    private static final Limit RECENT = Limit.of(50);

    private final BroadcastRepository broadcasts;
    private final BroadcastChatRepository chats;

    public ChatQueryService(final BroadcastRepository broadcasts, final BroadcastChatRepository chats) {
        this.broadcasts = broadcasts;
        this.chats = chats;
    }

    /** 최신 50건을 고른 뒤 오래된 순서로 돌려준다. 같은 시각은 id 로 순서를 고정한다. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> recent(final long broadcastId) {
        if (!broadcasts.existsById(broadcastId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        }
        return chats.findByBroadcastIdOrderByCreatedAtDescIdDesc(broadcastId, RECENT).reversed().stream()
            .map(ChatMessageResponse::from)
            .toList();
    }
}
