package com.shoppinglive.live.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.integration.member.MemberProfileClient;
import com.shoppinglive.live.integration.member.MemberProfileException;
import com.shoppinglive.live.integration.member.MemberProfileException.Reason;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("회원 채팅 작성")
class ChatWriteTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean MemberProfileClient members;

    @BeforeEach
    void memberReturnsDisplayName() {
        willReturn("회원A").given(members).displayName(any(), any());
    }

    private long broadcast(final String name, final String status) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = ?, started_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
        return id;
    }

    private ResultActions write(final long id, final String bearer, final Object content) throws Exception {
        final var request = post("/v1/broadcasts/{id}/chats", id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(content == null ? Map.of() : Map.of("content", content)));
        return mvc.perform(bearer == null ? request : request.header("Authorization", bearer));
    }

    private int chatCount() {
        return jdbc.queryForObject("SELECT count(*) FROM broadcast_chat", Integer.class);
    }

    @DisplayName("LIVE 방송에 쓰면 인증 회원·서버 시각·당시 표시 이름으로 한 건 저장하고 201을 반환한다")
    @Test
    void savesOneChatAsTheAuthenticatedMember() throws Exception {
        final long id = broadcast("write-live", "LIVE");
        final String bearer = userBearer();
        final int before = chatCount();
        final Instant started = Instant.now().minusSeconds(1);

        write(id, bearer, "  안녕하세요\n반가워요  ")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.messageId").isNumber())
            .andExpect(jsonPath("$.data.broadcastId").value(id))
            .andExpect(jsonPath("$.data.displayName").value("회원A"))
            .andExpect(jsonPath("$.data.content").value("안녕하세요\n반가워요"))
            .andExpect(jsonPath("$.data.createdAt").isString())
            .andExpect(jsonPath("$.data.memberId").doesNotExist());

        assertThat(chatCount()).isEqualTo(before + 1);
        final Map<String, Object> row = jdbc.queryForMap(
            "SELECT member_id, display_name, content, created_at FROM broadcast_chat WHERE broadcast_id = ?", id);
        assertThat(row.get("member_id").toString()).isEqualTo(JwtTestTokens.MEMBER_A);
        assertThat(row.get("display_name")).isEqualTo("회원A");
        assertThat(row.get("content")).isEqualTo("안녕하세요\n반가워요");
        assertThat(jdbc.queryForObject("SELECT created_at FROM broadcast_chat WHERE broadcast_id = ?",
            java.sql.Timestamp.class, id).toInstant()).isBetween(started, Instant.now().plusSeconds(1));
        verify(members).displayName(bearer, JwtTestTokens.MEMBER_A);
    }

    @DisplayName("ADMIN 역할도 채팅을 쓸 수 있다")
    @Test
    void adminMayWrite() throws Exception {
        write(broadcast("write-admin", "LIVE"), adminBearer(), "공지입니다").andExpect(status().isCreated());
    }

    @DisplayName("비회원·폐기 토큰은 401, 세션 확인 장애는 503이며 Member 이름 조회도 저장도 하지 않는다")
    @Test
    void unauthenticatedWritesChangeNothing() throws Exception {
        final long id = broadcast("write-auth", "LIVE");
        final String bearer = userBearer();
        final int before = chatCount();

        write(id, null, "hi").andExpect(status().isUnauthorized());
        write(id, "Bearer bad", "hi").andExpect(status().isUnauthorized());
        accessSessions.revoke();
        write(id, bearer, "hi").andExpect(status().isUnauthorized());
        accessSessions.reset();
        accessSessions.fail();
        write(id, bearer, "hi").andExpect(status().isServiceUnavailable());

        assertThat(chatCount()).isEqualTo(before);
        verify(members, never()).displayName(any(), any());
    }

    @DisplayName("Member 가 토큰을 거절하면 401, 이름을 확인할 수 없으면 503이며 저장하지 않는다")
    @Test
    void memberProfileFailureSavesNothing() throws Exception {
        final long id = broadcast("write-member", "LIVE");
        final int before = chatCount();

        willThrow(new MemberProfileException(Reason.UNAUTHORIZED)).given(members).displayName(any(), any());
        write(id, userBearer(), "hi").andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        willThrow(new MemberProfileException(Reason.UNAVAILABLE)).given(members).displayName(any(), any());
        write(id, userBearer(), "hi").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        assertThat(chatCount()).isEqualTo(before);
    }

    @DisplayName("공백만 있거나 201자 이상이거나 content 가 없으면 400이고 code point 200자는 허용한다")
    @Test
    void contentBoundaryIsCountedInCodePointsAfterStrip() throws Exception {
        final long id = broadcast("write-input", "LIVE");
        final int before = chatCount();

        write(id, userBearer(), " \n\t ").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        write(id, userBearer(), "가".repeat(201)).andExpect(status().isBadRequest());
        write(id, userBearer(), null).andExpect(status().isBadRequest());
        assertThat(chatCount()).isEqualTo(before);

        // 이모지는 UTF-16 으로 2칸이지만 code point 로는 1자다.
        write(id, userBearer(), " " + "😀".repeat(200) + " ").andExpect(status().isCreated());
        assertThat(chatCount()).isEqualTo(before + 1);
    }

    @DisplayName("PREPARING·ENDED 방송은 409, 없는 방송은 404이며 저장하지 않는다")
    @Test
    void onlyLiveBroadcastAcceptsChats() throws Exception {
        final int before = chatCount();

        write(broadcast("write-preparing", "PREPARING"), userBearer(), "hi").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        write(broadcast("write-ended", "ENDED"), userBearer(), "hi").andExpect(status().isConflict());
        write(Long.MAX_VALUE, userBearer(), "hi").andExpect(status().isNotFound());

        assertThat(chatCount()).isEqualTo(before);
    }

    @DisplayName("Redis 에 연결할 수 없어 실시간 전달에 실패해도 201 이고 저장된 채팅을 조회할 수 있다")
    @Test
    void writeSucceedsWhenRealtimeDeliveryFails() throws Exception {
        // 테스트 설정의 Redis 포트는 닫혀 있다.
        final long id = broadcast("write-no-redis", "LIVE");

        write(id, userBearer(), "전달 실패").andExpect(status().isCreated());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/v1/broadcasts/{id}/chats", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].content").value("전달 실패"));
    }
}
