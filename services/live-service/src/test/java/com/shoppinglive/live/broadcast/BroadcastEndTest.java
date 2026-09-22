package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("방송 종료 (상태 전이 제약, 멱등성, 동시 종료 요청)")
class BroadcastEndTest {
    @Autowired BroadcastService service;
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    /** #65 의 start() 가 아직 없으므로 LIVE 전제는 DB 를 직접 세팅해 만든다. */
    private void forceLive(final Broadcast broadcast) {
        new JdbcTemplate(dataSource).update(
            "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
            broadcast.getId());
    }

    private Broadcast register(final String title) {
        return service.register(UUID.randomUUID().toString(),
            new BroadcastInput(title, Instant.parse("2026-01-01T00:00:00Z"),
                "arn:aws:ivs:channel/" + title, "https://example.live-video.net/" + title));
    }

    @DisplayName("PREPARING 상태의 방송을 종료하려 하면 409로 거절한다")
    @Test
    void endingAPreparingBroadcastIsRejected() throws Exception {
        final Broadcast broadcast = register("end-preparing");
        mvc.perform(post("/v1/admin/broadcasts/{id}/end", broadcast.getId()))
            .andExpect(status().isConflict());
    }

    @DisplayName("존재하지 않는 방송을 종료하면 404를 반환한다")
    @Test
    void endingMissingBroadcastIs404() throws Exception {
        mvc.perform(post("/v1/admin/broadcasts/{id}/end", 999_999L))
            .andExpect(status().isNotFound());
    }

    @DisplayName("종료를 반복 호출해도 최초 종료 시각이 보존된다")
    @Test
    void repeatedEndPreservesFirstEndedAt() throws Exception {
        final Broadcast broadcast = register("end-repeat");
        forceLive(broadcast);

        mvc.perform(post("/v1/admin/broadcasts/{id}/end", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ENDED"));
        final Instant first = service.get(broadcast.getId()).getEndedAt();

        mvc.perform(post("/v1/admin/broadcasts/{id}/end", broadcast.getId()))
            .andExpect(status().isOk());
        assertThat(service.get(broadcast.getId()).getEndedAt()).isEqualTo(first);
        assertThat(service.get(broadcast.getId()).getStatus()).isEqualTo(BroadcastStatus.ENDED);
    }

    @DisplayName("LIVE 방송을 종료하면 ENDED 상태와 종료 시각이 기록된다")
    @Test
    void endedBroadcastIsNoLongerEditable() {
        final Broadcast broadcast = register("end-then-edit");
        forceLive(broadcast);
        final Broadcast ended = service.end(broadcast.getId());
        assertThat(ended.getStatus()).isEqualTo(BroadcastStatus.ENDED);
        assertThat(ended.getEndedAt()).isNotNull();
    }

    @DisplayName("동시에 종료를 요청해도 200/409만 반환하고 500이 나지 않는다")
    @Test
    void concurrentEndRequestsNeverReturnServerError() throws Exception {
        final Broadcast broadcast = register("end-concurrent");
        forceLive(broadcast);

        final CyclicBarrier gate = new CyclicBarrier(2);
        final Callable<Integer> call = () -> {
            gate.await();
            return mvc.perform(post("/v1/admin/broadcasts/{id}/end", broadcast.getId()))
                .andReturn().getResponse().getStatus();
        };
        final List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final List<Future<Integer>> futures = List.of(pool.submit(call), pool.submit(call));
            statuses = List.of(futures.get(0).get(), futures.get(1).get());
        }

        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(200, 409));
        assertThat(statuses).contains(200);
        assertThat(service.get(broadcast.getId()).getStatus()).isEqualTo(BroadcastStatus.ENDED);
    }
}
