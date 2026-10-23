package com.shoppinglive.notification.inbox.service;

import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 알림을 지운다. 하루에 한 번 돈다.
 *
 * <p>지워도 되는 근거는 <b>알림이 원본이 아니라 사본</b>이라는 점이다. 결제와 환불 기록은 Commerce DB 에 영구히 남고, 알림은 그 사실을 알린 쪽지다.
 *
 * <p>이게 없으면 알림이 영원히 쌓인다.
 *
 * <p>한 번에 지우는 양에 상한을 둔다. 지울 게 더 많으면 다음 주기에 이어서 지운다. 한 transaction 에서 너무 많이 지우면 그 사이 조회가 느려진다.
 */
@Component
public class NotificationRetentionScheduler {

    private static final int DELETE_LIMIT = 10_000;

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionScheduler.class);

    private final NotificationWriteRepository notifications;
    private final Duration retention;

    public NotificationRetentionScheduler(
            NotificationWriteRepository notifications,
            @Value("${notification.retention:P30D}") Duration retention) {
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("알림 보관 기간은 0보다 커야 합니다");
        }
        this.notifications = notifications;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT24H")
    public void run() {
        try {
            int deleted =
                    notifications.deleteCreatedBefore(Instant.now().minus(retention), DELETE_LIMIT);
            if (deleted > 0) {
                log.info("old notifications deleted count={} retention={}", deleted, retention);
            }
        } catch (RuntimeException e) {
            // 실패한 주기를 기록하고 다음 예약 실행에서 다시 시도한다.
            log.error("notification retention sweep failed", e);
        }
    }
}
