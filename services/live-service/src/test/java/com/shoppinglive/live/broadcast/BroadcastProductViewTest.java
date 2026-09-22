package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastProductViewResponse;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastProductViewService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("방송 상품 공개·관리 조회 (정렬, 구매 가능 여부, 누락 처리, 외부 장애)")
class BroadcastProductViewTest {
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastProductService links;
    @Autowired BroadcastProductViewService view;
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

    @DisplayName("상품은 연결 순서대로 productId와 salesId를 함께 반환한다")
    @Test
    void productsAreReturnedInConnectionOrderWithBothIdentifiers() {
        final Broadcast broadcast = register("view-order");
        links.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));

        final List<BroadcastProductViewResponse> products = view.forPublic(broadcast.getId());
        assertThat(products).extracting(BroadcastProductViewResponse::productId)
            .containsExactly(2L, 1L);
        assertThat(products.getFirst().salesId()).isEqualTo(102L);
        assertThat(products.getFirst().name()).isEqualTo("Local demo product 2");
    }

    @DisplayName("품절 상품은 보이되 구매 불가이고 판매중 상품만 구매 가능하다")
    @Test
    void soldOutIsVisibleButNotPurchasableAndOnSaleIs() {
        final Broadcast broadcast = register("view-purchasable");
        links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        links.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));

        assertThat(view.forPublic(broadcast.getId()))
            .extracting(BroadcastProductViewResponse::status,
                BroadcastProductViewResponse::purchasable)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("ON_SALE", true),
                org.assertj.core.groups.Tuple.tuple("SOLD_OUT", false));
    }

    @DisplayName("삭제된 상품은 공개 조회에서 숨기고 관리 조회에서는 missing으로 표시한다")
    @Test
    void publicViewHidesDeletedProductsWhileAdminViewMarksThemMissing() {
        final Broadcast broadcast = register("view-missing");
        final BroadcastProduct link = links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        // stub 에 없는 productId 로 바꿔 "정상 조회에서 사라진 연결"을 만든다.
        new JdbcTemplate(dataSource).update(
            "UPDATE broadcast_product SET product_id = 98 WHERE id = ?", link.getId());

        assertThat(view.forPublic(broadcast.getId())).isEmpty();
        assertThat(view.forAdmin(broadcast.getId())).singleElement()
            .satisfies(product -> {
                assertThat(product.missing()).isTrue();
                assertThat(product.linkId()).isEqualTo(link.getId());
                assertThat(product.position()).isZero();
            });
    }

    @DisplayName("방송 중 추가한 상품이 다음 조회에 즉시 반영된다")
    @Test
    void liveChangesShowUpOnTheNextQuery() {
        final Broadcast broadcast = register("view-live-change");
        links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        assertThat(view.forPublic(broadcast.getId())).hasSize(1);
        links.link(broadcast.getId(), 2L, versionOf(broadcast.getId()));
        assertThat(view.forPublic(broadcast.getId())).hasSize(2);
    }

    @DisplayName("상품이 없으면 빈 목록, 없는 방송이면 404를 반환한다")
    @Test
    void broadcastWithoutProductsIsEmptyAndMissingBroadcastIs404() throws Exception {
        final Broadcast broadcast = register("view-empty");
        mvc.perform(get("/v1/broadcasts/{id}/products", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/admin/broadcasts/{id}/products", 999_999L))
            .andExpect(status().isNotFound());
    }

    @DisplayName("공개·관리 상품 조회 API가 공통 응답 envelope로 반환한다")
    @Test
    void httpViewsReturnEnvelope() throws Exception {
        final Broadcast broadcast = register("view-http");
        links.link(broadcast.getId(), 1L, versionOf(broadcast.getId()));
        mvc.perform(get("/v1/broadcasts/{id}/products", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].productId").value(1))
            .andExpect(jsonPath("$.data[0].salesId").value(101));
        mvc.perform(get("/v1/admin/broadcasts/{id}/products", broadcast.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].position").value(0));
    }

    /** 외부 장애는 빈 목록으로 숨기지 않고 상품 endpoint 전체를 503 으로 만든다. */
    @Nested
    @SpringBootTest(properties = "live.products.mode=disabled")
    @AutoConfigureMockMvc
    @DisplayName("외부 상품 서비스가 중단된 경우")
    class WhenUpstreamIsDown {
        @Autowired BroadcastService broadcasts;
        @Autowired MockMvc mvc;
        @Autowired DataSource dataSource;

        @DisplayName("외부 장애 시 상품 endpoint는 503이지만 방송 기본 정보 조회는 정상이다")
        @Test
        void productEndpointIs503ButBasicInfoStillWorks() throws Exception {
            final Broadcast broadcast = broadcasts.register(UUID.randomUUID().toString(),
                new BroadcastInput("down", Instant.parse("2026-01-01T00:00:00Z"),
                    "arn:aws:ivs:channel/down", "https://example.live-video.net/down"));
            new JdbcTemplate(dataSource).update("""
                INSERT INTO broadcast_product (created_at, updated_at, broadcast_id, product_id,
                    sales_id, position) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 1, 101, 0)
                """, broadcast.getId());

            mvc.perform(get("/v1/broadcasts/{id}/products", broadcast.getId()))
                .andExpect(status().isServiceUnavailable());
            mvc.perform(get("/v1/broadcasts/{id}", broadcast.getId()))
                .andExpect(status().isOk());
        }
    }
}
