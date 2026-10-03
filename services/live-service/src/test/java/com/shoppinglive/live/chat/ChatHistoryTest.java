package com.shoppinglive.live.chat;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("최근 채팅 조회")
class ChatHistoryTest extends LiveSecuritySupport {
    private static final Instant BASE = Instant.parse("2026-10-03T11:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;

    private long broadcast(final String name) {
        return broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name, BASE,
            "arn:aws:ivs:ap-northeast-2:1:channel/" + name, "https://example.com/" + name + ".m3u8")).getId();
    }

    private void chat(final long broadcastId, final String content, final Instant createdAt) {
        jdbc.update("""
            INSERT INTO broadcast_chat (broadcast_id, member_id, display_name, content, created_at)
            VALUES (?, ?, '회원', ?, ?)
            """, broadcastId, UUID.randomUUID(), content, Timestamp.from(createdAt));
    }

    @DisplayName("토큰 없이 조회하며 Member 세션 확인 장애와 무관하다")
    @Test
    void anonymousReadDoesNotDependOnMemberSessionAuthority() throws Exception {
        final long id = broadcast("chat-anonymous");
        chat(id, "안녕하세요", BASE);
        accessSessions.fail();

        mvc.perform(get("/v1/broadcasts/{id}/chats", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].messageId").isNumber())
            .andExpect(jsonPath("$.data[0].broadcastId").value(id))
            .andExpect(jsonPath("$.data[0].displayName").value("회원"))
            .andExpect(jsonPath("$.data[0].content").value("안녕하세요"))
            .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-03T11:00:00Z"))
            .andExpect(jsonPath("$.data[0].memberId").doesNotExist());
    }

    @DisplayName("50건을 넘으면 최신 50건만 오래된 순서로 반환한다")
    @Test
    void returnsLatestFiftyInChronologicalOrder() throws Exception {
        final long id = broadcast("chat-latest-fifty");
        for (int i = 1; i <= 52; i++) {
            chat(id, "m" + i, BASE.plusSeconds(i));
        }

        mvc.perform(get("/v1/broadcasts/{id}/chats", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(50)))
            .andExpect(jsonPath("$.data[0].content").value("m3"))
            .andExpect(jsonPath("$.data[49].content").value("m52"));
    }

    @DisplayName("작성 시각이 같으면 messageId 순서로 고정한다")
    @Test
    void sameTimestampIsOrderedByMessageId() throws Exception {
        final long id = broadcast("chat-same-time");
        chat(id, "first", BASE);
        chat(id, "second", BASE);
        chat(id, "third", BASE);

        mvc.perform(get("/v1/broadcasts/{id}/chats", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].content").value("first"))
            .andExpect(jsonPath("$.data[1].content").value("second"))
            .andExpect(jsonPath("$.data[2].content").value("third"));
    }

    @DisplayName("다른 방송의 채팅은 섞이지 않고 채팅이 없는 준비 방송은 빈 목록이다")
    @Test
    void chatsAreScopedToTheBroadcastAndPreparingIsEmpty() throws Exception {
        final long other = broadcast("chat-other");
        chat(other, "다른 방송", BASE);
        final long preparing = broadcast("chat-preparing");

        mvc.perform(get("/v1/broadcasts/{id}/chats", preparing))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @DisplayName("없는 방송은 404, 숫자가 아닌 ID는 400이다")
    @Test
    void missingBroadcastIs404AndMalformedIdIs400() throws Exception {
        mvc.perform(get("/v1/broadcasts/{id}/chats", Long.MAX_VALUE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/v1/broadcasts/abc/chats"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
