package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("방송 부분 수정의 실제 JSON 요청 계약")
class BroadcastPatchHttpTest {
    private static final Instant SCHEDULED_AT = Instant.parse("2026-09-22T10:00:00Z");
    private static final String CHANNEL_ARN = "arn:aws:ivs:ap-northeast-2:123:channel/original";
    private static final String PLAYBACK_URL = "https://example.com/original.m3u8";

    @Autowired BroadcastService service;
    @Autowired BroadcastRepository repository;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private Broadcast broadcast;

    @BeforeEach
    void registerBroadcast() {
        broadcast = service.register("patch-http-" + UUID.randomUUID(),
            new BroadcastInput("original", SCHEDULED_AT, CHANNEL_ARN, PLAYBACK_URL));
    }

    @AfterEach
    void removeBroadcast() {
        repository.deleteById(broadcast.getId());
    }

    private ResultActions edit(final String body, final long version) throws Exception {
        return mvc.perform(patch("/v1/admin/broadcasts/{id}", broadcast.getId())
            .param("version", Long.toString(version))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    @Test
    @DisplayName("제목만 보내면 제목을 바꾸고 나머지 필드를 유지한다")
    void titleOnlyKeepsOtherFields() throws Exception {
        edit("{\"title\":\"renamed\"}", broadcast.getVersion())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("renamed"))
            .andExpect(jsonPath("$.data.scheduledAt").value(SCHEDULED_AT.toString()))
            .andExpect(jsonPath("$.data.channelArn").value(CHANNEL_ARN))
            .andExpect(jsonPath("$.data.playbackUrl").value(PLAYBACK_URL));
        assertThat(repository.findById(broadcast.getId()).orElseThrow().getVersion())
            .isGreaterThan(broadcast.getVersion());
    }

    @Test
    @DisplayName("빈 객체는 모든 필드와 version을 유지한다")
    void emptyObjectKeepsAllFields() throws Exception {
        edit("{}", broadcast.getVersion()).andExpect(status().isOk());
        assertUnchanged();
    }

    @Test
    @DisplayName("예약 시각만 보내면 나머지 필드를 유지하며 시각을 수정한다")
    void scheduledAtOnlyUpdatesTime() throws Exception {
        edit("{\"scheduledAt\":\"2026-10-01T12:00:00Z\"}", broadcast.getVersion())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scheduledAt").value("2026-10-01T12:00:00Z"))
            .andExpect(jsonPath("$.data.title").value("original"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "scheduledAt", "channelArn", "playbackUrl"})
    @DisplayName("어느 필드든 명시적 null은 400이며 저장된 값을 변경하지 않는다")
    void explicitNullIsRejected(final String field) throws Exception {
        edit("{\"" + field + "\":null}", broadcast.getVersion())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertUnchanged();
    }

    @Test
    @DisplayName("부분 요청에서도 제목 길이 검증이 적용된다")
    void invalidTitleIsRejected() throws Exception {
        edit("{\"title\":\"" + "a".repeat(101) + "\"}", broadcast.getVersion())
            .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    @DisplayName("빈 예약 시각을 null로 변환하더라도 400으로 거절한다")
    void emptyScheduledAtIsRejected() throws Exception {
        edit("{\"scheduledAt\":\"\"}", broadcast.getVersion())
            .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    @DisplayName("IVS 필드 하나만 수정하면 400이며 함께 보내면 수정된다")
    void ivsFieldsStillRequirePair() throws Exception {
        edit("{\"channelArn\":\"arn:new\"}", broadcast.getVersion())
            .andExpect(status().isBadRequest());
        assertUnchanged();
        edit("{\"channelArn\":\"arn:new\",\"playbackUrl\":\"https://example.com/new.m3u8\"}",
            broadcast.getVersion())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.channelArn").value("arn:new"));
    }

    @Test
    @DisplayName("잘못된 version의 부분 수정은 409이며 값을 바꾸지 않는다")
    void staleVersionIsRejected() throws Exception {
        edit("{\"title\":\"renamed\"}", broadcast.getVersion() + 1)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIVE", "ENDED"})
    @DisplayName("준비 상태가 아닌 방송의 부분 수정은 409이다")
    void nonPreparingStateIsRejected(final String state) throws Exception {
        jdbc.update("UPDATE broadcast SET status = ? WHERE id = ?", state, broadcast.getId());
        edit("{\"title\":\"renamed\"}", broadcast.getVersion())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        assertUnchanged();
    }

    private void assertUnchanged() {
        final Broadcast stored = repository.findById(broadcast.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("original");
        assertThat(stored.getScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(stored.getChannelArn()).isEqualTo(CHANNEL_ARN);
        assertThat(stored.getPlaybackUrl()).isEqualTo(PLAYBACK_URL);
        assertThat(stored.getVersion()).isEqualTo(broadcast.getVersion());
    }
}
