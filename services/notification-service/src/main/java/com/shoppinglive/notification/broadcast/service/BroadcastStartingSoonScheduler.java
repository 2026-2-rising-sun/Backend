package com.shoppinglive.notification.broadcast.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분마다 곧 시작하는 방송을 확인한다.
 *
 * <p>보낼 시각을 메모리 타이머로 재지 않는다. 서버를 재시작하면 예약이 사라져서 <b>배포할 때마다 알림이 누락된다.</b> 보낼 시각의 근거는 DB(방송 일정 사본)에
 * 있고, 이 스케줄러는 그걸 주기적으로 훑는다.
 *
 * <p>Commerce 의 주문 만료 정리와 같은 주기, 같은 방식이다.
 */
@Component
public class BroadcastStartingSoonScheduler {

    private static final Logger log = LoggerFactory.getLogger(BroadcastStartingSoonScheduler.class);

    private final BroadcastStartingSoonService service;

    public BroadcastStartingSoonScheduler(BroadcastStartingSoonService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void run() {
        try {
            int created = service.notifyStartingSoon(Instant.now());
            if (created > 0) {
                log.info("방송 시작 임박 알림 생성 완료: 생성 건수={}", created);
            }
        } catch (RuntimeException e) {
            // 던지면 다음 주기가 돌지 않는다. 한 주기가 실패해도 1분 뒤 다시 시도해야 한다.
            log.error("방송 시작 임박 알림 일괄 처리 실패", e);
        }
    }
}
