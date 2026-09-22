package com.shoppinglive.live.broadcast;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.application.BroadcastStartService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.integration.ivs.IvsConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** #61 공개 상세에 #64 의 영상 준비 상태를 연결한다. 업무 상태와 영상 상태는 분리된다. */
@SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=true"})
@AutoConfigureMockMvc
@DisplayName("공개 시청 연결 (업무 상태와 영상 준비 상태의 분리)")
class PublicViewingTest {
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastProductService links;
    @Autowired BroadcastStartService start;
    @Autowired MockMvc mvc;

    static Broadcast register(final BroadcastService broadcasts, final String name) {
        final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/" + name;
        return broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput(name, Instant.parse("2026-01-01T00:00:00Z"), arn,
                IvsConfiguration.stubPlaybackUrl(arn)));
    }

    private Broadcast live(final String name) {
        final Broadcast broadcast = register(broadcasts, name);
        links.link(broadcast.getId(), 1L, broadcasts.get(broadcast.getId()).getVersion());
        return start.start(broadcast.getId(), broadcasts.get(broadcast.getId()).getVersion());
    }

    @DisplayName("진행 중 방송 상세는 재생 URL과 READY 영상 상태를 노출한다")
    @Test
    void liveDetailExposesPlaybackUrlAndReadyVideoStatus() throws Exception {
        final Broadcast broadcast = live("viewing-ready");
        mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("진행"))
            .andExpect(jsonPath("$.data.playbackAllowed").value(true))
            .andExpect(jsonPath("$.data.videoStatus").value("READY"))
            .andExpect(jsonPath("$.data.playbackUrl").exists())
            .andExpect(jsonPath("$.data.channelArn").doesNotExist());
    }

    @DisplayName("예정 방송 상세는 재생 정보도 영상 상태도 제공하지 않는다")
    @Test
    void preparingDetailHasNoPlaybackAndNoVideoStatus() throws Exception {
        final Broadcast broadcast = register(broadcasts, "viewing-preparing");
        mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.playbackAllowed").value(false))
            .andExpect(jsonPath("$.data.videoStatus").doesNotExist())
            .andExpect(jsonPath("$.data.playbackUrl").doesNotExist());
    }

    @DisplayName("종료된 방송은 채널이 재사용되더라도 재생을 허용하지 않는다")
    @Test
    void endedBroadcastOffersNoPlaybackEvenThoughTheChannelIsReused() throws Exception {
        final Broadcast broadcast = live("viewing-ended");
        mvc.perform(post("/v1/admin/broadcasts/{id}/end", broadcast.getId()))
            .andExpect(status().isOk());
        mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("종료"))
            .andExpect(jsonPath("$.data.playbackAllowed").value(false))
            .andExpect(jsonPath("$.data.playbackUrl").doesNotExist())
            .andExpect(jsonPath("$.data.videoStatus").doesNotExist());
    }

    /** OBS 가 잠시 끊겨도 업무 상태 LIVE 는 유지되고 영상만 NOT_READY 로 표시된다. */
    @Nested
    @SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=false"})
    @AutoConfigureMockMvc
    @DisplayName("송출자가 일시적으로 끊긴 경우")
    class WhenBroadcasterDisconnects {
        @Autowired BroadcastService broadcasts;
        @Autowired MockMvc mvc;
        @Autowired javax.sql.DataSource dataSource;

        @DisplayName("송출이 끊겨도 업무 상태는 진행이고 영상 상태만 NOT_READY가 된다")
        @Test
        void businessStatusStaysLiveWhileVideoIsNotReady() throws Exception {
            final Broadcast broadcast = register(broadcasts, "viewing-disconnect");
            // start() 는 IVS 준비를 요구하므로 "시작 후 단절" 상황은 DB 상태로 만든다.
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
                broadcast.getId());

            mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("진행"))
                .andExpect(jsonPath("$.data.playbackAllowed").value(true))
                .andExpect(jsonPath("$.data.videoStatus").value("NOT_READY"))
                .andExpect(jsonPath("$.data.playbackUrl").exists());
        }
    }
}
