package com.shoppinglive.live.broadcast.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.dto.ChatMessageResponse;
import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastChat;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastChatRepository;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.client.member.MemberProfileClient;
import com.shoppinglive.live.client.member.MemberProfileException;
import com.shoppinglive.live.stream.ChatRelayBudget;
import com.shoppinglive.live.stream.StreamEvent;
import com.shoppinglive.live.stream.StreamRelay;
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
    private final StreamRelay relay;
    private final ChatRelayBudget budget;
    private final TransactionTemplate transaction;

    public ChatWriteService(
            final BroadcastRepository broadcasts,
            final BroadcastChatRepository chats,
            final MemberProfileClient members,
            final StreamRelay relay,
            final ChatRelayBudget budget,
            final PlatformTransactionManager manager) {
        this.broadcasts = broadcasts;
        this.chats = chats;
        this.members = members;
        this.relay = relay;
        this.budget = budget;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * 표시 이름 조회(외부 호출)는 트랜잭션 밖에서 먼저 한다. 방송 상태는 저장 직전에 잠금 없이 읽으므로 종료와 동시에 도착한 채팅은 저장될 수 있다. 이는 허용한
     * 동작이다. 실시간 전달은 보장하지 않는다. 전달에 실패해도 저장된 채팅과 201 응답은 그대로다.
     *
     * <p>방송별 초당 예산을 넘은 일반 채팅은 저장만 하고 실시간 전달은 생략한다. 사람이 읽을 수 있는 속도를 넘는 채팅은 보여줘도 읽히지 않는다. 작성자는 응답으로
     * 자기 채팅을 바로 보고, 이벤트 판정은 저장된 기록으로 한다. 판매자 채팅은 예산과 무관하게 전달한다(YouTube slow mode 가 채널 소유자를 제한하지 않는
     * 것과 같다). 방송별 소유자 칼럼이 없어 SELLER 역할이면 모두 해당한다.
     */
    public ChatMessageResponse write(
            final long broadcastId,
            final String memberId,
            final boolean seller,
            final String authorization,
            final String rawContent) {
        final String content = rawContent.strip();
        final int length = content.codePointCount(0, content.length());
        if (length < 1 || length > MAX_CONTENT_CODE_POINTS) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "content는 공백을 제외하고 1~200자여야 합니다.");
        }
        final String displayName = displayName(authorization, memberId);

        final ChatMessageResponse saved =
                transaction.execute(
                        status -> {
                            final Broadcast broadcast =
                                    broadcasts
                                            .findById(broadcastId)
                                            .orElseThrow(
                                                    () ->
                                                            new BusinessException(
                                                                    ErrorCode.NOT_FOUND,
                                                                    "방송을 찾을 수 없습니다."));
                            if (broadcast.getStatus() != BroadcastStatus.LIVE) {
                                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
                            }
                            // DB timestamp 는 마이크로초 정밀도라 응답과 재조회 값이 같도록 맞춘다.
                            return ChatMessageResponse.from(
                                    chats.save(
                                            new BroadcastChat(
                                                    broadcastId,
                                                    UUID.fromString(memberId),
                                                    displayName,
                                                    content,
                                                    Instant.now().truncatedTo(ChronoUnit.MICROS))));
                        });
        // 커밋 뒤에만 발행한다. 저장에 실패하면 여기까지 오지 않는다.
        if (seller || budget.tryAcquire(broadcastId, saved.createdAt())) {
            relay.publish(
                    broadcastId,
                    new StreamEvent("chat.created", String.valueOf(saved.messageId()), saved));
        }
        return saved;
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
