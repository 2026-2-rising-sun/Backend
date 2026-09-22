package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BroadcastProductOrderTest {
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastProductService products;
    @Autowired BroadcastProductRepository links;
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    private Broadcast register(final String title) {
        return broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput(title, Instant.parse("2026-01-01T00:00:00Z"),
                "arn:aws:ivs:channel/" + title, "https://example.live-video.net/" + title));
    }

    private long versionOf(final long id) {
        return broadcasts.get(id).getVersion();
    }

    /** stub 의 연결 가능 상품은 1(ON_SALE)과 2(SOLD_OUT) 두 개다. */
    private List<Long> linkTwo(final long broadcastId) {
        final BroadcastProduct first = products.link(broadcastId, 1L, versionOf(broadcastId));
        final BroadcastProduct second = products.link(broadcastId, 2L, versionOf(broadcastId));
        return List.of(first.getId(), second.getId());
    }

    private List<Long> positionsOf(final long broadcastId) {
        return links.findByBroadcastIdOrderByPositionAsc(broadcastId).stream()
            .map(BroadcastProduct::getId).toList();
    }

    @Test
    void swappingTheWholePermutationReorders() {
        final Broadcast broadcast = register("order-swap");
        final List<Long> ids = linkTwo(broadcast.getId());
        products.reorder(broadcast.getId(), List.of(ids.get(1), ids.get(0)),
            versionOf(broadcast.getId()));
        assertThat(positionsOf(broadcast.getId())).containsExactly(ids.get(1), ids.get(0));
    }

    @Test
    void liveBroadcastCanStillBeReordered() {
        final Broadcast broadcast = register("order-live");
        final List<Long> ids = linkTwo(broadcast.getId());
        new JdbcTemplate(dataSource).update(
            "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
            broadcast.getId());
        products.reorder(broadcast.getId(), List.of(ids.get(1), ids.get(0)),
            versionOf(broadcast.getId()));
        assertThat(positionsOf(broadcast.getId())).containsExactly(ids.get(1), ids.get(0));
    }

    @Test
    void endedBroadcastCannotBeReordered() {
        final Broadcast broadcast = register("order-ended");
        final List<Long> ids = linkTwo(broadcast.getId());
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE broadcast SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP "
            + "WHERE id = ?", broadcast.getId());
        assertThatThrownBy(() -> products.reorder(broadcast.getId(), ids,
            versionOf(broadcast.getId()))).hasMessageContaining("종료된 방송");
    }

    @Test
    void missingDuplicateOrForeignLinkIdsAreRejectedAndOrderIsUnchanged() {
        final Broadcast broadcast = register("order-invalid");
        final List<Long> ids = linkTwo(broadcast.getId());
        final Broadcast other = register("order-other");
        final Long foreign = products.link(other.getId(), 1L, versionOf(other.getId())).getId();

        assertThatThrownBy(() -> products.reorder(broadcast.getId(), List.of(ids.get(0)),
            versionOf(broadcast.getId()))).hasMessageContaining("전체 연결 상품 순열");
        assertThatThrownBy(() -> products.reorder(broadcast.getId(),
            List.of(ids.get(0), ids.get(0)), versionOf(broadcast.getId())))
            .hasMessageContaining("중복");
        assertThatThrownBy(() -> products.reorder(broadcast.getId(),
            List.of(ids.get(0), foreign), versionOf(broadcast.getId())))
            .hasMessageContaining("전체 연결 상품 순열");

        assertThat(positionsOf(broadcast.getId())).containsExactlyElementsOf(ids);
    }

    @Test
    void staleExpectedVersionCollidesWithLinkChanges() {
        final Broadcast broadcast = register("order-version");
        final List<Long> ids = linkTwo(broadcast.getId());
        final long stale = versionOf(broadcast.getId()) - 1;
        assertThatThrownBy(() -> products.reorder(broadcast.getId(), ids, stale))
            .hasMessageContaining("다시 조회");
    }

    @Test
    void reorderBumpsVersionSoAFollowingUnlinkWithTheOldVersionFails() {
        final Broadcast broadcast = register("order-bump");
        final List<Long> ids = linkTwo(broadcast.getId());
        final long before = versionOf(broadcast.getId());
        products.reorder(broadcast.getId(), List.of(ids.get(1), ids.get(0)), before);
        assertThat(versionOf(broadcast.getId())).isGreaterThan(before);
        assertThatThrownBy(() -> products.unlink(broadcast.getId(), ids.get(0), before))
            .hasMessageContaining("다시 조회");
    }

    @Test
    void httpReorderReturnsNewOrderAndRejectsMissingVersion() throws Exception {
        final Broadcast broadcast = register("order-http");
        final List<Long> ids = linkTwo(broadcast.getId());
        mvc.perform(put("/v1/admin/broadcasts/{id}/products/order", broadcast.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"linkIds\":[" + ids.get(1) + "," + ids.get(0) + "],"
                    + "\"expectedVersion\":" + versionOf(broadcast.getId()) + "}"))
            .andExpect(status().isOk());
        mvc.perform(put("/v1/admin/broadcasts/{id}/products/order", broadcast.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"linkIds\":[" + ids.get(0) + "," + ids.get(1) + "]}"))
            .andExpect(status().isBadRequest());
    }
}
