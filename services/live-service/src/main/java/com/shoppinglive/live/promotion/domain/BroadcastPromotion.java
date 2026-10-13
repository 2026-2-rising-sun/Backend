package com.shoppinglive.live.promotion.domain;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 방송 중 이벤트(프로모션)의 집계 루트. 쿠폰 전용이 아니며, {@code type} 과 자유 JSON 문자열인
 * {@code attributes} 로 종류를 표현한다. 라이브는 attributes 를 해석하지 않는다.
 *
 * <p>상태 전이는 모두 이 엔티티 안에서 결정되고 전이가 일어났는지(boolean) 반환한다. 호출자는
 * 반환값을 보고 Kafka outbox 기록 여부를 정한다. 반복 호출(멱등)에서는 false 가 돌아온다.
 */
@Entity
@Table(name = "broadcast_promotion")
public class BroadcastPromotion extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private long broadcastId;

    @Column(nullable = false, unique = true, updatable = false, length = 128)
    private String requestKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, updatable = false, length = 64)
    private String type;

    @Column(nullable = false, updatable = false, length = 100)
    private String title;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String attributes;

    @Column(updatable = false)
    private Integer durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PromotionStatus status = PromotionStatus.READY;

    private Instant openedAt;
    private Instant closesAt;
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PromotionCloseReason closeReason;

    @Version
    private long version;

    protected BroadcastPromotion() {
    }

    public BroadcastPromotion(final long broadcastId, final String requestKey,
                              final String fingerprint, final String type, final String title,
                              final String attributes, final Integer durationSeconds) {
        this.broadcastId = broadcastId;
        this.requestKey = requestKey;
        this.fingerprint = fingerprint;
        this.type = type;
        this.title = title;
        this.attributes = attributes;
        this.durationSeconds = durationSeconds;
    }

    /**
     * READY → OPEN 전이면 true, 이미 OPEN 이면 false(멱등), CLOSED 면 409.
     * {@code durationSeconds} 가 있으면 {@code closesAt = openedAt + duration} 으로 자동 만료 시각을 박는다.
     */
    public boolean open(final Instant now) {
        if (status == PromotionStatus.OPEN) {
            return false;
        }
        if (status == PromotionStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CONFLICT, "닫힌 프로모션은 다시 열 수 없습니다.");
        }
        this.status = PromotionStatus.OPEN;
        this.openedAt = now.truncatedTo(ChronoUnit.MICROS);
        if (durationSeconds != null) {
            this.closesAt = openedAt.plusSeconds(durationSeconds);
        }
        return true;
    }

    /**
     * READY·OPEN → CLOSED 전이면 true, 이미 CLOSED 면 false. READY 상태에서 판매자가 close 를
     * 부르면 reason 을 {@link PromotionCloseReason#CANCELED} 로 교정한다(열리지 않은 것을 "닫는" 건
     * 취소에 가깝다). READY 상태의 방송 종료·만료는 들어온 reason 을 그대로 유지한다.
     */
    public boolean close(final Instant now, final PromotionCloseReason reason) {
        if (status == PromotionStatus.CLOSED) {
            return false;
        }
        PromotionCloseReason resolved = reason;
        if (status == PromotionStatus.READY && reason == PromotionCloseReason.SELLER_CLOSED) {
            resolved = PromotionCloseReason.CANCELED;
        }
        this.status = PromotionStatus.CLOSED;
        this.closedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.closeReason = resolved;
        return true;
    }

    /**
     * 이 프로모션이 OPEN 상태를 거쳤는지. close 호출 뒤 Kafka 발행 여부 판정에 쓴다.
     * READY→CLOSED(취소·방송 종료)로 바로 간 경우 false 라 발행하지 않는다.
     */
    public boolean wasOpen() {
        return openedAt != null;
    }

    public long getBroadcastId() {
        return broadcastId;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getAttributes() {
        return attributes;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public PromotionStatus getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public PromotionCloseReason getCloseReason() {
        return closeReason;
    }

    public long getVersion() {
        return version;
    }
}
