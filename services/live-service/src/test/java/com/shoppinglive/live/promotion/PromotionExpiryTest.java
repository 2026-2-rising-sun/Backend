package com.shoppinglive.live.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.promotion.api.PromotionInput;
import com.shoppinglive.live.promotion.application.PromotionExpiryScheduler;
import com.shoppinglive.live.promotion.application.PromotionService;
import com.shoppinglive.live.promotion.domain.BroadcastPromotion;
import com.shoppinglive.live.promotion.domain.PromotionCloseReason;
import com.shoppinglive.live.promotion.domain.PromotionStatus;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트는 {@code live.promotion.expiry.enabled=false} 로 스케줄러 자동 실행을 끄고,
 * 수동으로 {@link PromotionExpiryScheduler#expireDue(Instant)} 를 호출해 결정론으로 검증한다.
 * expireDue 는 {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * 가 꺼져 있어도 빈이 등록되지 않을 수 있으니, 테스트는 그 빈을 조건부로 가져오지 않고 직접 조립한다.
 */
@SpringBootTest
@DisplayName("프로모션 자동 만료 (closes_at 지난 OPEN 을 EXPIRED 로 닫는다)")
class PromotionExpiryTest extends LiveSecuritySupport {

    @Autowired BroadcastService broadcasts;
    @Autowired PromotionService promotions;
    @Autowired JdbcTemplate jdbc;

    private long liveBroadcast() {
        final String name = UUID.randomUUID().toString();
        final long id = broadcasts.register(name, new BroadcastInput(name, Instant.now(),
            "arn:aws:ivs:channel/" + name, "https://example.live-video.net/" + name)).getId();
        jdbc.update("UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return id;
    }

    private PromotionExpiryScheduler scheduler() {
        // 스케줄러 빈은 테스트에서 꺼놨으므로 손으로 만든다. 생성자 의존은 PromotionService 뿐이다.
        return new PromotionExpiryScheduler(promotions);
    }

    @DisplayName("closes_at 이 지난 OPEN 은 EXPIRED 로 닫히고, 아직 안 지난 것은 그대로 OPEN")
    @Test
    void expireDueClosesPastOpenOnly() {
        final long id = liveBroadcast();
        final BroadcastPromotion created = promotions.create(id, UUID.randomUUID().toString(),
            new PromotionInput("COUPON_DROP", "할인", Map.of(), 10));
        final BroadcastPromotion opened = promotions.open(id, created.getId());
        assertThat(opened.getStatus()).isEqualTo(PromotionStatus.OPEN);

        // closes_at 을 1분 전으로 밀어 과거로 만든다
        final Instant past = Instant.now().minusSeconds(60);
        jdbc.update("UPDATE broadcast_promotion SET closes_at = ? WHERE id = ?",
            Timestamp.from(past), opened.getId());

        final int closed = scheduler().expireDue(Instant.now());
        assertThat(closed).isEqualTo(1);

        final BroadcastPromotion after = promotions.list(id).get(0);
        assertThat(after.getStatus()).isEqualTo(PromotionStatus.CLOSED);
        assertThat(after.getCloseReason()).isEqualTo(PromotionCloseReason.EXPIRED);
        assertThat(after.wasOpen()).isTrue();
    }

    @DisplayName("duration 미지정이면 closes_at 이 null 이라 스케줄러 대상이 아니다")
    @Test
    void withoutDurationNothingToExpire() {
        final long id = liveBroadcast();
        final BroadcastPromotion created = promotions.create(id, UUID.randomUUID().toString(),
            new PromotionInput("COUPON_DROP", "할인", Map.of(), null));
        promotions.open(id, created.getId());

        assertThat(scheduler().expireDue(Instant.now())).isZero();
        assertThat(promotions.list(id).get(0).getStatus()).isEqualTo(PromotionStatus.OPEN);
    }
}
