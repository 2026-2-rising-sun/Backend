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
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("방송-상품 연결·해제 (판매 상태 제약, 낙관적 잠금, 정렬 압축, 재시도 멱등성)")
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

    @DisplayName("판매중·품절 상품은 연결할 수 있고 salesId는 productId와 별개로 저장된다")
    @Test
    void onSaleAndSoldOutProductsCanBeLinkedAndSalesIdIsSeparate() {
        final Broadcast broadcast = register("link-ok");
        final BroadcastProduct onSale = products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThat(onSale.getProductId()).isEqualTo(1L);
        assertThat(onSale.getSalesId()).isEqualTo(101L).isNotEqualTo(onSale.getProductId());

        final BroadcastProduct soldOut = products.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        assertThat(soldOut.getPosition()).isEqualTo(1);
    }

    @DisplayName("준비중·비공개·존재하지 않는 상품은 연결을 거절한다")
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

    @DisplayName("이미 연결된 상품을 다시 연결하면 충돌로 거절한다")
    @Test
    void duplicateProductIsConflict() {
        final Broadcast broadcast = register("link-dup");
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThatThrownBy(() -> products.link(broadcast.getId(), 1L, versionOf(broadcast.getId())))
            .hasMessageContaining("이미 연결된 상품");
    }

    @DisplayName("낡은 expectedVersion으로 연결하면 충돌로 거절한다")
    @Test
    void staleExpectedVersionIsConflict() {
        final Broadcast broadcast = register("link-version");
        final long stale = versionOf(broadcast.getId());
        products.link(broadcast.getId(), 1L, stale);
        assertThatThrownBy(() -> products.link(broadcast.getId(), 2L, stale))
            .hasMessageContaining("다시 조회");
    }

    @DisplayName("상품을 연결하면 방송 version이 올라가 다른 수정과 충돌한다")
    @Test
    void linkingBumpsBroadcastVersionSoOtherEditsCollide() {
        final Broadcast broadcast = register("link-bump");
        final long before = versionOf(broadcast.getId());
        products.link(broadcast.getId(), 1L, before);
        assertThat(versionOf(broadcast.getId())).isGreaterThan(before);
    }

    @DisplayName("종료된 방송에는 상품을 연결할 수 없다")
    @Test
    void endedBroadcastIsReadOnly() {
        final Broadcast broadcast = register("link-ended");
        forceLive(broadcast.getId());
        products.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        broadcasts.end(broadcast.getId());
        assertThatThrownBy(() -> products.link(broadcast.getId(), 2L, versionOf(broadcast.getId())))
            .hasMessageContaining("종료된 방송");
    }

    @DisplayName("LIVE 방송은 마지막 남은 연결 상품을 해제할 수 없다")
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

    @DisplayName("이미 해제된 연결의 재삭제는 204, 남의 방송 연결 삭제는 404다")
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

    @DisplayName("연결을 해제하면 남은 상품의 정렬 위치가 앞으로 당겨진다")
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

    @DisplayName("HTTP 연결은 201을 반환하고 없는 방송은 404를 반환한다")
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

    @DisplayName("필수 필드가 빠진 연결 요청은 400을 반환한다")
    @Test
    void httpLinkWithoutRequiredFieldsIs400() throws Exception {
        final Broadcast broadcast = register("link-bad");
        mvc.perform(post("/v1/admin/broadcasts/{id}/products", broadcast.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1}"))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("처음 발급받은 version으로 삭제를 재시도해도 204이고 version은 변하지 않는다")
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

    @DisplayName("종료된 방송은 없는 linkId의 해제도 no-op이 아니라 거절한다")
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
