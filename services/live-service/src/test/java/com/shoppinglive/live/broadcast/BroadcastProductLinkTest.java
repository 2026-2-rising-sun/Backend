package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastProductRepository;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** stub 은 1=ON_SALE, 2=SOLD_OUT, 3=READY, 4=PRIVATE, 그 밖은 미존재다. */
@SpringBootTest
@AutoConfigureMockMvc
class BroadcastProductLinkTest {
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

    private void forceLive(final long id) {
        new JdbcTemplate(dataSource).update(
            "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    private long versionOf(final long id) {
        return broadcasts.get(id).getVersion();
    }

    @Test
    void onSaleAndSoldOutProductsCanBeLinkedAndSalesIdIsSeparate() {
        final Broadcast broadcast = register("link-ok");
        final BroadcastProduct onSale = products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThat(onSale.getProductId()).isEqualTo(1L);
        assertThat(onSale.getSalesId()).isEqualTo(101L).isNotEqualTo(onSale.getProductId());

        final BroadcastProduct soldOut = products.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        assertThat(soldOut.getPosition()).isEqualTo(1);
    }

    @Test
    void readyAndPrivateAndMissingProductsAreRejected() {
        final Broadcast broadcast = register("link-reject");
        assertThatThrownBy(() -> products.link(broadcast.getId(), 3L, versionOf(broadcast.getId())))
            .hasMessageContaining("판매중 또는 품절");
        assertThatThrownBy(() -> products.link(broadcast.getId(), 4L, versionOf(broadcast.getId())))
            .hasMessageContaining("판매중 또는 품절");
        assertThatThrownBy(() -> products.link(broadcast.getId(), 99L, versionOf(broadcast.getId())))
            .hasMessageContaining("찾을 수 없습니다");
        assertThat(links.findByBroadcastIdOrderByPositionAsc(broadcast.getId())).isEmpty();
    }

    @Test
    void duplicateProductIsConflict() {
        final Broadcast broadcast = register("link-dup");
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThatThrownBy(() -> products.link(broadcast.getId(), 1L, versionOf(broadcast.getId())))
            .hasMessageContaining("이미 연결된 상품");
    }

    @Test
    void staleExpectedVersionIsConflict() {
        final Broadcast broadcast = register("link-version");
        final long stale = versionOf(broadcast.getId());
        products.link(broadcast.getId(), 1L, stale);
        assertThatThrownBy(() -> products.link(broadcast.getId(), 2L, stale))
            .hasMessageContaining("다시 조회");
    }

    @Test
    void linkingBumpsBroadcastVersionSoOtherEditsCollide() {
        final Broadcast broadcast = register("link-bump");
        final long before = versionOf(broadcast.getId());
        products.link(broadcast.getId(), 1L, before);
        assertThat(versionOf(broadcast.getId())).isGreaterThan(before);
    }

    @Test
    void endedBroadcastIsReadOnly() {
        final Broadcast broadcast = register("link-ended");
        forceLive(broadcast.getId());
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        broadcasts.end(broadcast.getId());
        assertThatThrownBy(() -> products.link(broadcast.getId(), 2L, versionOf(broadcast.getId())))
            .hasMessageContaining("종료된 방송");
    }

    @Test
    void liveBroadcastKeepsItsLastProduct() {
        final Broadcast broadcast = register("link-last");
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        final BroadcastProduct second =
            products.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        forceLive(broadcast.getId());

        products.unlink(broadcast.getId(), second.getId(), versionOf(broadcast.getId()));
        final Long lastLinkId =
            links.findByBroadcastIdOrderByPositionAsc(broadcast.getId()).getFirst().getId();
        assertThatThrownBy(() ->
            products.unlink(broadcast.getId(), lastLinkId, versionOf(broadcast.getId())))
            .hasMessageContaining("마지막 연결 상품");
    }

    @Test
    void unlinkingAnAlreadyRemovedLinkSucceedsButAForeignLinkIs404() throws Exception {
        final Broadcast owner = register("unlink-owner");
        final Broadcast other = register("unlink-other");
        final BroadcastProduct link = products.link(owner.getId(), 1L, versionOf(owner.getId()));

        mvc.perform(delete("/v1/admin/broadcasts/{id}/products/{linkId}", owner.getId(),
                link.getId()).param("expectedVersion", String.valueOf(versionOf(owner.getId()))))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/v1/admin/broadcasts/{id}/products/{linkId}", owner.getId(),
                link.getId()).param("expectedVersion", String.valueOf(versionOf(owner.getId()))))
            .andExpect(status().isNoContent());

        final BroadcastProduct ownersLink = products.link(owner.getId(), 2L, versionOf(owner.getId()));
        mvc.perform(delete("/v1/admin/broadcasts/{id}/products/{linkId}", other.getId(),
                ownersLink.getId())
                .param("expectedVersion", String.valueOf(versionOf(other.getId()))))
            .andExpect(status().isNotFound());
    }

    @Test
    void unlinkingCompactsPositions() {
        final Broadcast broadcast = register("unlink-positions");
        final BroadcastProduct first = products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        products.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        products.unlink(broadcast.getId(), first.getId(), versionOf(broadcast.getId()));
        assertThat(links.findByBroadcastIdOrderByPositionAsc(broadcast.getId()))
            .singleElement()
            .satisfies(link -> {
                assertThat(link.getProductId()).isEqualTo(2L);
                assertThat(link.getPosition()).isZero();
            });
    }

    @Test
    void httpLinkReturns201AndMissingBroadcastIs404() throws Exception {
        final Broadcast broadcast = register("link-http");
        mvc.perform(post("/v1/admin/broadcasts/{id}/products", broadcast.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"expectedVersion\":" + versionOf(broadcast.getId()) + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.productId").value(1))
            .andExpect(jsonPath("$.data.salesId").value(101));

        mvc.perform(post("/v1/admin/broadcasts/{id}/products", 999_999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"expectedVersion\":0}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void httpLinkWithoutRequiredFieldsIs400() throws Exception {
        final Broadcast broadcast = register("link-bad");
        mvc.perform(post("/v1/admin/broadcasts/{id}/products", broadcast.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void retryingDeleteWithTheOriginallyIssuedVersionStillReturns204() throws Exception {
        final Broadcast broadcast = register("unlink-retry");
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        final BroadcastProduct target =
            products.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));

        // 클라이언트가 한 번 발급받은 버전. 응답이 유실된 재시도는 이 값을 그대로 다시 보낸다.
        final long issued = versionOf(broadcast.getId());

        mvc.perform(delete("/v1/admin/broadcasts/{id}/products/{linkId}", broadcast.getId(),
                target.getId()).param("expectedVersion", String.valueOf(issued)))
            .andExpect(status().isNoContent());

        final long afterDelete = versionOf(broadcast.getId());
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(delete("/v1/admin/broadcasts/{id}/products/{linkId}", broadcast.getId(),
                    target.getId()).param("expectedVersion", String.valueOf(issued)))
                .andExpect(status().isNoContent());
        }
        // no-op 삭제는 version 을 건드리지 않는다.
        assertThat(versionOf(broadcast.getId())).isEqualTo(afterDelete);
    }

    @Test
    void endedBroadcastRejectsUnlinkEvenForAMissingLinkId() {
        final Broadcast broadcast = register("unlink-ended-missing");
        forceLive(broadcast.getId());
        final BroadcastProduct target =
            products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        broadcasts.end(broadcast.getId());

        // 존재하지 않는(이미 해제된) linkId 라도 ENDED 는 읽기 전용이므로 no-op 취급하지 않는다.
        assertThatThrownBy(() ->
            products.unlink(broadcast.getId(), target.getId() + 999, versionOf(broadcast.getId())))
            .hasMessageContaining("종료된 방송");
    }
}
