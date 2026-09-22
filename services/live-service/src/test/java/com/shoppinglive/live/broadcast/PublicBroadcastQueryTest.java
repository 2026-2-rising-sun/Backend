package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.PublicBroadcastResponse;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.application.PublicBroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PublicBroadcastQueryTest {
    @Autowired BroadcastService broadcasts;
    @Autowired PublicBroadcastService publicBroadcasts;
    @Autowired MockMvc mvc;

    private Broadcast register(final String title) {
        return broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput(title, Instant.parse("2026-01-01T00:00:00Z"),
                "arn:aws:ivs:channel/" + title, "https://example.live-video.net/" + title));
    }

    @Test
    void preparingBroadcastIsShownAsScheduledWithoutPlayback() {
        final Broadcast broadcast = register("public-preparing");
        final PublicBroadcastResponse response = publicBroadcasts.get(broadcast.getId());
        assertThat(response.status()).isEqualTo("예정");
        assertThat(response.playbackUrl()).isNull();
        assertThat(response.playbackAllowed()).isFalse();
    }

    @Test
    void listIsPagedAndNeverFails() {
        register("public-list");
        assertThat(publicBroadcasts.list(0, 20).getContent()).isNotEmpty();
        assertThat(publicBroadcasts.list(0, 500).getPageable().getPageSize()).isEqualTo(100);
        assertThat(publicBroadcasts.list(999, 20).getContent()).isEmpty();
    }

    @Test
    void missingBroadcastIs404() throws Exception {
        mvc.perform(get("/v1/broadcasts/{id}", 999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void publicResponseHidesSecretFields() throws Exception {
        final Broadcast broadcast = register("public-secret");
        mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("public-secret"))
            .andExpect(jsonPath("$.data.channelArn").doesNotExist())
            .andExpect(jsonPath("$.data.requestKey").doesNotExist())
            .andExpect(jsonPath("$.data.fingerprint").doesNotExist())
            .andExpect(jsonPath("$.data.playbackUrl").doesNotExist());
    }

    @Test
    void publicListEndpointReturnsEnvelope() throws Exception {
        register("public-http-list");
        mvc.perform(get("/v1/broadcasts").param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
