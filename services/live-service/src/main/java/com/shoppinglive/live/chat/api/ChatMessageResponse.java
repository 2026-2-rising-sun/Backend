package com.shoppinglive.live.chat.api;

import com.shoppinglive.live.chat.domain.BroadcastChat;
import java.time.Instant;

/** 공개 채팅. 작성자 회원 ID 는 넣지 않는다. */
public record ChatMessageResponse(long messageId, long broadcastId, String displayName, String content,
                                  Instant createdAt) {
    public static ChatMessageResponse from(final BroadcastChat chat) {
        return new ChatMessageResponse(chat.getId(), chat.getBroadcastId(), chat.getDisplayName(),
            chat.getContent(), chat.getCreatedAt());
    }
}
