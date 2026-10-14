package com.shoppinglive.live.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.promotion.dto.PromotionInput;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.promotion.entity.PromotionStatus;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spring.datasource.driver-class-name=${LIVE_PROMOTION_TEST_DB_DRIVER:org.h2.Driver}",
            "spring.datasource.username=${LIVE_PROMOTION_TEST_DB_USER:sa}",
            "spring.datasource.password=${LIVE_PROMOTION_TEST_DB_PASSWORD:}",
            "spring.datasource.url=${LIVE_PROMOTION_TEST_POSTGRES_URL:jdbc:h2:mem:promotionlifecycletest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}"
        })
@DisplayName("프로모션 상태 전이 (방송 상태·멱등·공개 노출)")
class PromotionLifecycleTest extends LiveSecuritySupport {

    @Autowired BroadcastService broadcasts;
    @Autowired PromotionService promotions;
    @Autowired JdbcTemplate jdbc;

    private long broadcast(String status) {
        final String name = UUID.randomUUID().toString();
        final long id =
                broadcasts
                        .register(
                                name,
                                new BroadcastInput(
                                        name,
                                        Instant.now(),
                                        "arn:aws:ivs:channel/" + name,
                                        "https://example.live-video.net/" + name))
                        .getId();
        if (status.equals("LIVE") || status.equals("ENDED")) {
            jdbc.update(
                    "UPDATE broadcast SET status = ?, started_at = CURRENT_TIMESTAMP WHERE id = ?",
                    status,
                    id);
        } else {
            jdbc.update("UPDATE broadcast SET status = ? WHERE id = ?", status, id);
        }
        return id;
    }

    private BroadcastPromotion create(long broadcastId) {
        return promotions.create(
                broadcastId,
                UUID.randomUUID().toString(),
                new PromotionInput("COUPON_DROP", "할인", Map.of("couponPolicyId", 7), null));
    }

    @DisplayName("PREPARING 방송에서는 열 수 없다 — 409")
    @Test
    void openOnPreparingBroadcastIsConflict() {
        final long id = broadcast("PREPARING");
        final BroadcastPromotion promotion = create(id);
        assertThatThrownBy(() -> promotions.open(id, promotion.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("진행 중인 방송");
    }

    @DisplayName("LIVE 방송에서는 열리고, 반복 호출은 멱등으로 OPEN 유지")
    @Test
    void openOnLiveIsIdempotent() {
        final long id = broadcast("LIVE");
        final BroadcastPromotion promotion = create(id);

        BroadcastPromotion opened = promotions.open(id, promotion.getId());
        assertThat(opened.getStatus()).isEqualTo(PromotionStatus.OPEN);
        Instant firstOpenedAt = opened.getOpenedAt();

        BroadcastPromotion reopened = promotions.open(id, promotion.getId());
        assertThat(reopened.getOpenedAt()).isEqualTo(firstOpenedAt);
        assertThat(reopened.getStatus()).isEqualTo(PromotionStatus.OPEN);
    }

    @DisplayName("닫은 뒤 다시 열면 409, 공개 목록에선 사라진다")
    @Test
    void reopenAfterCloseIsConflict() {
        final long id = broadcast("LIVE");
        final BroadcastPromotion promotion = create(id);
        promotions.open(id, promotion.getId());
        promotions.close(id, promotion.getId());

        assertThat(promotions.listOpen(id)).isEmpty();
        assertThatThrownBy(() -> promotions.open(id, promotion.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("닫힌 프로모션");
    }

    @DisplayName("종료된 방송에는 더 못 만든다 — 409")
    @Test
    void createOnEndedBroadcastIsConflict() {
        final long id = broadcast("ENDED");
        assertThatThrownBy(
                        () ->
                                promotions.create(
                                        id,
                                        UUID.randomUUID().toString(),
                                        new PromotionInput("COUPON_DROP", "할인", Map.of(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("종료된 방송");
    }
}
