package com.shoppinglive.live.broadcast.domain;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "broadcast")
public class Broadcast extends BaseEntity {
    @Column(nullable = false, unique = true, updatable = false, length = 128)
    private String requestKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false, length = 255)
    private String channelArn;

    @Column(nullable = false, length = 2048)
    private String playbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BroadcastStatus status = BroadcastStatus.PREPARING;

    private Instant startedAt;
    private Instant endedAt;

    @Version
    private long version;

    protected Broadcast() {
    }

    public Broadcast(final String requestKey, final String fingerprint, final String title,
                     final Instant scheduledAt, final String channelArn, final String playbackUrl) {
        this.requestKey = requestKey;
        this.fingerprint = fingerprint;
        this.title = title;
        this.scheduledAt = scheduledAt;
        this.channelArn = channelArn;
        this.playbackUrl = playbackUrl;
    }

    public void edit(final long expectedVersion, final String title, final Instant scheduledAt,
                     final String channelArn, final String playbackUrl) {
        if (status != BroadcastStatus.PREPARING) {
            throw new BusinessException(ErrorCode.CONFLICT, "준비 상태에서만 기본정보를 수정할 수 있습니다.");
        }
        if (version != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "방송이 변경되었습니다. 다시 조회하세요.");
        }
        this.title = title;
        this.scheduledAt = scheduledAt;
        this.channelArn = channelArn;
        this.playbackUrl = playbackUrl;
    }

    /**
     * LIVE → ENDED. 반복 종료는 최초 endedAt 을 보존하며 성공하고, 준비 상태 종료는 409 다.
     * 종료는 주문·결제·재고·송출에 관여하지 않는다.
     */
    public void end(final Instant now) {
        if (status == BroadcastStatus.PREPARING) {
            throw new BusinessException(ErrorCode.CONFLICT, "준비 상태의 방송은 종료할 수 없습니다.");
        }
        if (status == BroadcastStatus.ENDED) {
            return;
        }
        this.status = BroadcastStatus.ENDED;
        this.endedAt = now;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getChannelArn() {
        return channelArn;
    }

    public String getPlaybackUrl() {
        return playbackUrl;
    }

    public BroadcastStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public long getVersion() {
        return version;
    }
}
