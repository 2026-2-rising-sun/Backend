package com.shoppinglive.shopping.coupon.service;

import com.shoppinglive.shopping.client.commerce.HttpCouponSnapshotClient;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Commerce 쿠폰 스냅샷 API 로 쿠폰 replica 를 채우고 주기적으로 맞춘다. 초기 적재와 대조가 같은 코드다.
 *
 * <p>한 번의 실행은 두 단계다.
 *
 * <ol>
 *   <li>목록 API 를 끝까지 읽어 같은 version 규칙으로 반영한다. 이벤트를 놓친 새 쿠폰과 변경이 채워진다.
 *   <li>replica 에서는 아직 발급 중인데 목록에 없던 쿠폰을 단건 API 로 다시 읽는다. 일찍 종료된 쿠폰이 이 단계에서 정리된다.
 * </ol>
 *
 * <p>목록 페이지 전체는 한 시점의 스냅샷이 아니다. 그래도 version 이 큰 값만 반영되므로, 이벤트와 순서가 섞여도 replica 가 뒤로 가지 않는다. 실행 중
 * 실패하면 그때까지 반영한 쿠폰은 남기고 멈춘다. 다음 주기가 처음부터 다시 한다.
 */
public class CouponSync {

    private static final Logger log = LoggerFactory.getLogger(CouponSync.class);

    private final HttpCouponSnapshotClient commerce;
    private final CouponDisplayRepository replica;
    private final Clock clock;

    public CouponSync(
            HttpCouponSnapshotClient commerce, CouponDisplayRepository replica, Clock clock) {
        this.commerce = commerce;
        this.replica = replica;
        this.clock = clock;
    }

    // shortcut: Pod 마다 각자 실행한다 (반영이 멱등이라 결과는 같다), 쿠폰 수가 많아 Commerce 부하가 보이면 lock 을 둔다
    @Scheduled(
            initialDelayString = "${shopping.coupons.sync.initial-delay:10s}",
            fixedDelayString = "${shopping.coupons.sync.interval:10m}")
    public void run() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("coupon sync stopped: cause={}", e.toString(), e);
        }
    }

    /**
     * @return 새로 반영한 쿠폰 수
     */
    int sync() {
        return 0;
    }
}
