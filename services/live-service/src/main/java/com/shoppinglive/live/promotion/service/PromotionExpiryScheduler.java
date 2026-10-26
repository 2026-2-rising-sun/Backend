package com.shoppinglive.live.promotion.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * closes_at 이 지난 OPEN 프로모션을 EXPIRED 로 닫는다. 여러 pod 가 동시에 돌 수 있으므로 한 건을 처리할 때 낙관적 락 실패는 "다른 pod 가 이미
 * 처리했다" 로 보고 조용히 지나간다.
 *
 * <p>테스트는 {@code live.promotion.expiry.enabled=false} 로 끈 뒤 메서드를 직접 호출해 결정론적으로 검증한다. 운영 기본값은
 * 켬이다(matchIfMissing=true).
 */
@Component
@ConditionalOnProperty(
        prefix = "live.promotion.expiry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PromotionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromotionExpiryScheduler.class);

    private final PromotionService service;

    public PromotionExpiryScheduler(final PromotionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${live.promotion.expiry.delay:5s}")
    public void run() {
        expireDue(Instant.now());
    }

    /** 테스트에서 임의 시각으로 호출할 수 있도록 분리. 반환은 이번 호출로 실제 닫힌 건수. */
    public int expireDue(final Instant now) {
        final List<Long> candidates = service.findExpiredCandidateIds(now);
        int closed = 0;
        for (Long id : candidates) {
            try {
                if (service.expireOne(id).isPresent()) {
                    closed++;
                }
            } catch (ObjectOptimisticLockingFailureException concurrent) {
                log.debug("다른 파드에서 이미 처리한 프로모션: 프로모션 ID={}", id);
            }
        }
        return closed;
    }
}
