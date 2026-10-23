package com.shoppinglive.live.broadcast.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.dto.ChatMessageResponse;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("재연결 시 놓친 채팅 조회")
class ChatReplayTest extends LiveSecuritySupport {
    private static final Instant NOW = Instant.parse("2026-10-03T11:10:00Z");

    @Autowired ChatQueryService chats;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;

    private long broadcast(final String name) {
        return broadcasts
                .register(
                        UUID.randomUUID().toString(),
                        new BroadcastInput(
                                name,
                                NOW,
                                "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
                                "https://example.com/" + name + ".m3u8"))
                .getId();
    }

    private long chat(final long broadcastId, final String content, final Instant createdAt) {
        jdbc.update(
                """
            INSERT INTO broadcast_chat (broadcast_id, member_id, display_name, content, created_at)
            VALUES (?, ?, '회원', ?, ?)
            """,
                broadcastId,
                UUID.randomUUID(),
                content,
                Timestamp.from(createdAt));
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM broadcast_chat WHERE broadcast_id = ?",
                Long.class,
                broadcastId);
    }

    @DisplayName("마지막 채팅 5초 전부터 오래된 순서로 주고, 그보다 앞선 채팅은 주지 않는다")
    @Test
    void replaysFromFiveSecondsBeforeLastChat() {
        final long id = broadcast("replay-overlap");
        chat(id, "6초 전", NOW.minusSeconds(66));
        chat(id, "4초 전", NOW.minusSeconds(64));
        final long last = chat(id, "마지막으로 받음", NOW.minusSeconds(60));
        chat(id, "놓침1", NOW.minusSeconds(30));
        chat(id, "놓침2", NOW.minusSeconds(10));

        assertThat(chats.missedSince(id, last, NOW, 256))
                .extracting(ChatMessageResponse::content)
                .containsExactly("4초 전", "마지막으로 받음", "놓침1", "놓침2");
    }

    @DisplayName("마지막 채팅이 2분보다 오래됐으면 이어받지 않는다")
    @Test
    void doesNotReplayWhenLastChatIsOlderThanTwoMinutes() {
        final long id = broadcast("replay-window");
        final long last = chat(id, "오래전", NOW.minusSeconds(121));
        chat(id, "놓침", NOW.minusSeconds(10));

        assertThat(chats.missedSince(id, last, NOW, 256)).isEmpty();
    }

    @DisplayName("다른 방송의 채팅 번호나 없는 번호로는 이어받지 않는다")
    @Test
    void doesNotReplayForUnknownOrOtherBroadcastMessage() {
        final long mine = broadcast("replay-mine");
        final long other = broadcast("replay-other");
        chat(mine, "내 방송", NOW.minusSeconds(10));
        final long otherMessage = chat(other, "다른 방송", NOW.minusSeconds(20));

        assertThat(chats.missedSince(mine, otherMessage, NOW, 256)).isEmpty();
        assertThat(chats.missedSince(mine, Long.MAX_VALUE, NOW, 256)).isEmpty();
    }

    @DisplayName("개수 상한을 넘으면 최신 채팅만 오래된 순서로 준다")
    @Test
    void keepsLatestWhenOverLimit() {
        final long id = broadcast("replay-limit");
        final long last = chat(id, "마지막으로 받음", NOW.minusSeconds(60));
        chat(id, "놓침1", NOW.minusSeconds(30));
        chat(id, "놓침2", NOW.minusSeconds(20));
        chat(id, "놓침3", NOW.minusSeconds(10));

        assertThat(chats.missedSince(id, last, NOW, 2))
                .extracting(ChatMessageResponse::content)
                .containsExactly("놓침2", "놓침3");
    }
}
