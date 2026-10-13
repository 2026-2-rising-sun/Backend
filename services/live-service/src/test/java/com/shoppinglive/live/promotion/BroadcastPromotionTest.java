package com.shoppinglive.live.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.live.promotion.domain.BroadcastPromotion;
import com.shoppinglive.live.promotion.domain.PromotionCloseReason;
import com.shoppinglive.live.promotion.domain.PromotionStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("프로모션 도메인 상태 전이")
class BroadcastPromotionTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00.123456Z");

    private BroadcastPromotion ready() {
        return new BroadcastPromotion(42L, "req-" + Math.random(), "fp", "COUPON_DROP",
            "할인", "{\"couponPolicyId\":7}", 60);
    }

    @DisplayName("READY → OPEN 전이면 true 를 돌려주고, duration 이 있으면 closesAt 을 박는다")
    @Test
    void openTransitionsReadyToOpenAndStampsClosesAt() {
        BroadcastPromotion promotion = ready();
        assertThat(promotion.open(NOW)).isTrue();
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.OPEN);
        assertThat(promotion.getOpenedAt()).isEqualTo(NOW);
        assertThat(promotion.getClosesAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @DisplayName("duration 미지정이면 closesAt 은 null")
    @Test
    void openWithoutDurationLeavesClosesAtNull() {
        BroadcastPromotion promotion = new BroadcastPromotion(42L, "req-x", "fp", "T", "t",
            "{}", null);
        assertThat(promotion.open(NOW)).isTrue();
        assertThat(promotion.getClosesAt()).isNull();
    }

    @DisplayName("이미 OPEN 상태에서 다시 열면 멱등으로 false, 상태·시각은 유지")
    @Test
    void openOnOpenIsIdempotent() {
        BroadcastPromotion promotion = ready();
        promotion.open(NOW);
        Instant firstOpenedAt = promotion.getOpenedAt();

        assertThat(promotion.open(NOW.plusSeconds(10))).isFalse();
        assertThat(promotion.getOpenedAt()).isEqualTo(firstOpenedAt);
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.OPEN);
    }

    @DisplayName("닫힌 프로모션은 다시 열 수 없다 — 409")
    @Test
    void openOnClosedIsConflict() {
        BroadcastPromotion promotion = ready();
        promotion.open(NOW);
        promotion.close(NOW.plusSeconds(10), PromotionCloseReason.SELLER_CLOSED);

        assertThatThrownBy(() -> promotion.open(NOW.plusSeconds(20)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("닫힌 프로모션");
    }

    @DisplayName("OPEN → CLOSED(SELLER_CLOSED) 는 reason 그대로")
    @Test
    void closeOpenPromotionWithSellerCloseKeepsReason() {
        BroadcastPromotion promotion = ready();
        promotion.open(NOW);
        assertThat(promotion.close(NOW.plusSeconds(10), PromotionCloseReason.SELLER_CLOSED)).isTrue();
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.CLOSED);
        assertThat(promotion.getCloseReason()).isEqualTo(PromotionCloseReason.SELLER_CLOSED);
        assertThat(promotion.getClosedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(promotion.wasOpen()).isTrue();
    }

    @DisplayName("READY → CLOSED(SELLER_CLOSED) 는 CANCELED 로 교정된다")
    @Test
    void closeReadyPromotionWithSellerCloseBecomesCanceled() {
        BroadcastPromotion promotion = ready();
        assertThat(promotion.close(NOW, PromotionCloseReason.SELLER_CLOSED)).isTrue();
        assertThat(promotion.getCloseReason()).isEqualTo(PromotionCloseReason.CANCELED);
        assertThat(promotion.wasOpen()).isFalse();
    }

    @DisplayName("READY → CLOSED(BROADCAST_ENDED) 는 reason 그대로 유지")
    @Test
    void closeReadyPromotionWithBroadcastEndedKeepsReason() {
        BroadcastPromotion promotion = ready();
        assertThat(promotion.close(NOW, PromotionCloseReason.BROADCAST_ENDED)).isTrue();
        assertThat(promotion.getCloseReason()).isEqualTo(PromotionCloseReason.BROADCAST_ENDED);
        assertThat(promotion.wasOpen()).isFalse();
    }

    @DisplayName("이미 CLOSED 를 다시 닫으면 false 멱등")
    @Test
    void closeOnClosedIsIdempotent() {
        BroadcastPromotion promotion = ready();
        promotion.open(NOW);
        promotion.close(NOW.plusSeconds(10), PromotionCloseReason.SELLER_CLOSED);

        PromotionCloseReason first = promotion.getCloseReason();
        Instant firstClosedAt = promotion.getClosedAt();

        assertThat(promotion.close(NOW.plusSeconds(20), PromotionCloseReason.EXPIRED)).isFalse();
        assertThat(promotion.getCloseReason()).isEqualTo(first);
        assertThat(promotion.getClosedAt()).isEqualTo(firstClosedAt);
    }

    @DisplayName("시각은 DB 마이크로초 정밀도에 맞춰 잘라낸다")
    @Test
    void timestampsAreTruncatedToMicros() {
        BroadcastPromotion promotion = new BroadcastPromotion(42L, "req", "fp", "T", "t", "{}", 30);
        Instant nanos = Instant.parse("2026-10-25T12:00:00.123456789Z");
        promotion.open(nanos);
        assertThat(promotion.getOpenedAt().getNano() % 1000).isZero();
        assertThat(promotion.getClosesAt().getNano() % 1000).isZero();
    }
}
