package com.shoppinglive.live.chat.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.chat.api.ChatMessageResponse;
import com.shoppinglive.live.chat.domain.BroadcastChat;
import com.shoppinglive.live.chat.infrastructure.BroadcastChatRepository;
import com.shoppinglive.live.integration.member.MemberProfileClient;
import com.shoppinglive.live.integration.member.MemberProfileException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatWriteService {
    private static final int MAX_CONTENT_CODE_POINTS = 200;

    private final BroadcastRepository broadcasts;
    private final BroadcastChatRepository chats;
    private final MemberProfileClient members;
    private final TransactionTemplate transaction;

    public ChatWriteService(final BroadcastRepository broadcasts, final BroadcastChatRepository chats,
                            final MemberProfileClient members, final PlatformTransactionManager manager) {
        this.broadcasts = broadcasts;
        this.chats = chats;
        this.members = members;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * 표시 이름 조회(외부 호출)는 트랜잭션 밖에서 먼저 한다. 방송 상태는 저장 직전에 잠금 없이 읽으므로
     * 종료와 동시에 도착한 채팅은 저장될 수 있다. 이는 허용한 동작이다.
     */
    public ChatMessageResponse write(final long broadcastId, final String memberId, final String authorization,
                                     final String rawContent) {
        final String content = rawContent.strip();
        final int length = content.codePointCount(0, content.length());
        if (length < 1 || length > MAX_CONTENT_CODE_POINTS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "content는 공백을 제외하고 1~200자여야 합니다.");
        }
        final String displayName = displayName(authorization, memberId);

        return transaction.execute(status -> {
            final Broadcast broadcast = broadcasts.findById(broadcastId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
            if (broadcast.getStatus() != BroadcastStatus.LIVE) {
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
            }
            // DB timestamp 는 마이크로초 정밀도라 응답과 재조회 값이 같도록 맞춘다.
            return ChatMessageResponse.from(chats.save(new BroadcastChat(broadcastId, UUID.fromString(memberId),
                displayName, content, Instant.now().truncatedTo(ChronoUnit.MICROS))));
        });
    }

    private String displayName(final String authorization, final String memberId) {
        try {
            return members.displayName(authorization, memberId);
        } catch (MemberProfileException e) {
            if (e.reason() == MemberProfileException.Reason.UNAUTHORIZED) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "작성자 정보를 확인할 수 없습니다.");
        }
    }
}
