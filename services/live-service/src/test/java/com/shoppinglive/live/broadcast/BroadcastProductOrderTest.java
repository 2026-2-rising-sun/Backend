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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("#63 방송 상품 노출 순서 변경")
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
    @DisplayName("전체 순열을 뒤바꾸면 노출 순서가 바뀐다")
    void swappingTheWholePermutationReorders() {
        final Broadcast broadcast = register("order-swap");
        final List<Long> ids = linkTwo(broadcast.getId());
        products.reorder(broadcast.getId(), List.of(ids.get(1), ids.get(0)),
            versionOf(broadcast.getId()));
        assertThat(positionsOf(broadcast.getId())).containsExactly(ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("LIVE 방송도 노출 순서를 변경할 수 있다")
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
    @DisplayName("ENDED 방송은 노출 순서를 변경할 수 없다")
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
    @DisplayName("linkId 누락·중복·타 방송 소속이면 거절되고 기존 순서가 유지된다")
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
    @DisplayName("오래된 expectedVersion으로 정렬하면 연결 변경과 충돌해 거절된다")
    void staleExpectedVersionCollidesWithLinkChanges() {
        final Broadcast broadcast = register("order-version");
        final List<Long> ids = linkTwo(broadcast.getId());
        final long stale = versionOf(broadcast.getId()) - 1;
        assertThatThrownBy(() -> products.reorder(broadcast.getId(), ids, stale))
            .hasMessageContaining("다시 조회");
    }

    @Test
    @DisplayName("정렬은 version을 증가시켜 이후 예전 version으로 한 해제 요청을 실패시킨다")
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
    @DisplayName("HTTP PUT 정렬은 새 순서를 반환하고 version 누락은 400이다")
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

    @Test
    @DisplayName("동시 정렬 요청은 하나만 200이고 나머지는 409로 끝난다")
    void concurrentReordersResolveTo200And409() throws Exception {
        final Broadcast broadcast = register("order-concurrent");
        final List<Long> ids = linkTwo(broadcast.getId());
        final long version = versionOf(broadcast.getId());
        final String body = "{\"linkIds\":[" + ids.get(1) + "," + ids.get(0) + "],"
            + "\"expectedVersion\":" + version + "}";

        final CyclicBarrier gate = new CyclicBarrier(2);
        final Callable<Integer> call = () -> {
            gate.await();
            return mvc.perform(put("/v1/admin/broadcasts/{id}/products/order", broadcast.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        };
        final List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final List<Future<Integer>> futures = List.of(pool.submit(call), pool.submit(call));
            statuses = List.of(futures.get(0).get(), futures.get(1).get());
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(versionOf(broadcast.getId())).isEqualTo(version + 1);
    }
}
