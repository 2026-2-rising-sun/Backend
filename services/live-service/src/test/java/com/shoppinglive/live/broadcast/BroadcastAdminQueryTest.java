package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastResponse;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BroadcastAdminQueryTest {
    @Autowired BroadcastService service;
    @Autowired MockMvc mvc;

    private final Instant scheduled = Instant.parse("2026-01-01T00:00:00Z");

    private Broadcast register(final String title) {
        return service.register(UUID.randomUUID().toString(),
            new BroadcastInput(title, scheduled, "arn:aws:ivs:channel/" + title,
                "https://example.live-video.net/" + title + ".m3u8"));
    }

    @Test
    void listReturnsRegisteredBroadcastsNewestFirst() {
        final Broadcast older = register("older");
        final Broadcast newer = register("newer");
        final Page<BroadcastResponse> page = service.list(null, 0, 20);
        assertThat(page.getContent()).extracting(BroadcastResponse::id)
            .containsSubsequence(newer.getId(), older.getId());
    }

    @Test
    void statusFilterReturnsOnlyThatStatus() {
        register("preparing-only");
        final Page<BroadcastResponse> page = service.list(BroadcastStatus.PREPARING, 0, 20);
        assertThat(page.getContent()).isNotEmpty()
            .allMatch(b -> b.status() == BroadcastStatus.PREPARING);
    }

    @Test
    void liveAndEndedFiltersAreEmptyWhenNothingTransitioned() {
        register("still-preparing");
        assertThat(service.list(BroadcastStatus.LIVE, 0, 20).getContent()).isEmpty();
        assertThat(service.list(BroadcastStatus.ENDED, 0, 20).getContent()).isEmpty();
    }

    @Test
    void pageSizeIsCappedAtHundred() {
        assertThat(service.list(null, 0, 500).getPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    void emptyPageBeyondTheEndIsNotAnError() {
        register("one-page");
        assertThat(service.list(null, 999, 20).getContent()).isEmpty();
    }

    @Test
    void httpListAndDetailReturnBroadcast() throws Exception {
        final Broadcast broadcast = register("http-detail");
        mvc.perform(get("/v1/admin/broadcasts").param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/v1/admin/broadcasts/{id}", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("http-detail"))
            .andExpect(jsonPath("$.data.status").value("PREPARING"));
    }

    @Test
    void httpDetailOfMissingBroadcastIs404() throws Exception {
        mvc.perform(get("/v1/admin/broadcasts/{id}", 999_999L))
            .andExpect(status().isNotFound());
    }

    @Test
    void httpListWithInvalidStatusFilterIs400() throws Exception {
        mvc.perform(get("/v1/admin/broadcasts").param("status", "NOPE"))
            .andExpect(status().isBadRequest());
    }
}
