package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.application.BroadcastStartService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.integration.ivs.IvsConfiguration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=true"})
@AutoConfigureMockMvc
class BroadcastStartTest {
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastProductService links;
    @Autowired BroadcastStartService start;
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    /** stub IVS 는 channelArn 에서 시청 URL 을 유도한다. 일치시켜 등록해야 시작할 수 있다. */
    private Broadcast register(final String name) {
        final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/" + name;
        return broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput(name, Instant.parse("2026-01-01T00:00:00Z"), arn,
                IvsConfiguration.stubPlaybackUrl(arn)));
    }

    private long versionOf(final long id) {
        return broadcasts.get(id).getVersion();
    }

    private Broadcast readyToStart(final String name, final long productId) {
        final Broadcast broadcast = register(name);
        links.link(broadcast.getId(), productId, versionOf(broadcast.getId()));
        return broadcast;
    }

    @Test
    void soldOutOnlyBroadcastCanStartBecauseAvailableIsNotChecked() {
        final Broadcast broadcast = readyToStart("start-soldout", 2L);
        final Broadcast started = start.start(broadcast.getId(), versionOf(broadcast.getId()));
        assertThat(started.getStatus()).isEqualTo(BroadcastStatus.LIVE);
        assertThat(started.getStartedAt()).isNotNull();
    }

    @Test
    void repeatedStartPreservesFirstStartedAtWithoutRecheckingExternals() {
        final Broadcast broadcast = readyToStart("start-repeat", 1L);
        final Instant first =
            start.start(broadcast.getId(), versionOf(broadcast.getId())).getStartedAt();
        assertThat(start.start(broadcast.getId(), versionOf(broadcast.getId())).getStartedAt())
            .isEqualTo(first);
    }

    @Test
    void broadcastWithoutProductsCannotStart() {
        final Broadcast broadcast = register("start-noproducts");
        assertThatThrownBy(() -> start.start(broadcast.getId(), versionOf(broadcast.getId())))
            .hasMessageContaining("연결된 상품이 없습니다");
    }

    @Test
    void staleVersionIsConflictAndMissingBroadcastIs404() {
        final Broadcast broadcast = readyToStart("start-version", 1L);
        assertThatThrownBy(() ->
            start.start(broadcast.getId(), versionOf(broadcast.getId()) + 1))
            .hasMessageContaining("다시 조회");
        assertThatThrownBy(() -> start.start(999_999L, 0)).hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    void endedBroadcastCannotRestart() {
        final Broadcast broadcast = readyToStart("start-ended", 1L);
        start.start(broadcast.getId(), versionOf(broadcast.getId()));
        broadcasts.end(broadcast.getId());
        assertThatThrownBy(() -> start.start(broadcast.getId(), versionOf(broadcast.getId())))
            .hasMessageContaining("다시 시작할 수 없습니다");
    }

    @Test
    void mismatchedPlaybackUrlBlocksStart() {
        final Broadcast broadcast = broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput("start-mismatch", Instant.parse("2026-01-01T00:00:00Z"),
                "arn:aws:ivs:ap-northeast-2:1:channel/mismatch",
                "https://attacker.example/other.m3u8"));
        links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThatThrownBy(() -> start.start(broadcast.getId(), versionOf(broadcast.getId())))
            .hasMessageContaining("실제 URL과 다릅니다");
    }

    @Test
    void httpStartReturnsLiveAndRequiresVersion() throws Exception {
        final Broadcast broadcast = readyToStart("start-http", 1L);
        mvc.perform(post("/v1/admin/broadcasts/{id}/start", broadcast.getId())
                .param("expectedVersion", String.valueOf(versionOf(broadcast.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LIVE"));
        mvc.perform(post("/v1/admin/broadcasts/{id}/start", broadcast.getId()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void httpStartRetryWithTheOriginallyIssuedVersionIsIdempotent() throws Exception {
        final Broadcast broadcast = readyToStart("start-retry", 1L);
        // 클라이언트가 한 번 발급받은 버전. 응답이 유실된 재시도는 이 값을 그대로 다시 보낸다.
        final long issued = versionOf(broadcast.getId());

        mvc.perform(post("/v1/admin/broadcasts/{id}/start", broadcast.getId())
                .param("expectedVersion", String.valueOf(issued)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LIVE"));
        final Instant first = broadcasts.get(broadcast.getId()).getStartedAt();

        // 시작 성공으로 version 이 올랐지만 재시도는 같은 issued 로 온다 → 멱등 성공이어야 한다.
        assertThat(versionOf(broadcast.getId())).isNotEqualTo(issued);
        mvc.perform(post("/v1/admin/broadcasts/{id}/start", broadcast.getId())
                .param("expectedVersion", String.valueOf(issued)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LIVE"));
        assertThat(broadcasts.get(broadcast.getId()).getStartedAt()).isEqualTo(first);
    }

    @Test
    void startingDoesNotTouchStockOfTheConnectedSoldOutProduct() {
        // 재고 조회·차감·예약을 하지 않으므로 available 0 인 상품만 연결해도 시작한다.
        final Broadcast broadcast = readyToStart("start-nostock", 2L);
        assertThat(start.start(broadcast.getId(), versionOf(broadcast.getId())).getStatus())
            .isEqualTo(BroadcastStatus.LIVE);
    }

    /** 송출 중이 아니면 업무 전이를 허용하지 않는다. */
    @Nested
    @SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=false"})
    class WhenChannelIsNotBroadcasting {
        @Autowired BroadcastService broadcasts;
        @Autowired BroadcastProductService links;
        @Autowired BroadcastStartService start;

        @Test
        void notReadyChannelBlocksStart() {
            final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/notready";
            final Broadcast broadcast = broadcasts.register(UUID.randomUUID().toString(),
                new BroadcastInput("notready", Instant.parse("2026-01-01T00:00:00Z"), arn,
                    IvsConfiguration.stubPlaybackUrl(arn)));
            links.link(broadcast.getId(), 1L, broadcasts.get(broadcast.getId()).getVersion());
            assertThatThrownBy(() -> start.start(broadcast.getId(),
                broadcasts.get(broadcast.getId()).getVersion()))
                .hasMessageContaining("송출 중이 아닙니다");
        }
    }

    /** Commerce 장애는 업무 조건 불충족(409)이 아니라 인프라 장애(503)다. */
    @Nested
    @SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=true",
        "live.products.mode=disabled"})
    @AutoConfigureMockMvc
    class WhenCommerceIsDown {
        @Autowired BroadcastService broadcasts;
        @Autowired MockMvc mvc;
        @Autowired DataSource dataSource;

        @Test
        void salesLookupFailureIs503() throws Exception {
            final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/down";
            final Broadcast broadcast = broadcasts.register(UUID.randomUUID().toString(),
                new BroadcastInput("down", Instant.parse("2026-01-01T00:00:00Z"), arn,
                    IvsConfiguration.stubPlaybackUrl(arn)));
            new JdbcTemplate(dataSource).update("""
                INSERT INTO broadcast_product (created_at, updated_at, broadcast_id, product_id,
                    sales_id, position) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 1, 101, 0)
                """, broadcast.getId());
            mvc.perform(post("/v1/admin/broadcasts/{id}/start", broadcast.getId())
                    .param("expectedVersion",
                        String.valueOf(broadcasts.get(broadcast.getId()).getVersion())))
                .andExpect(status().isServiceUnavailable());
        }
    }
}
