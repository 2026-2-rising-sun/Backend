package com.shoppinglive.notification.broadcast.service;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 방송 시작 10분 전에 신청자 전원에게 알림을 만든다.
 *
 * <p>시작 알림만으로는 늦다. 라이브커머스는 동시 시청자 수가 매출을 결정하는데, 시작한 뒤에 알리면 앞부분을 놓친다.
 *
 * <p><b>이 알림은 이벤트가 오는 순간 만들어지지 않는다.</b> 일정 이벤트로 "언제 보낼지" 만 알아내고, 그 시각이 되면 만든다. 그래서 받는 쪽이 이벤트가 아니라
 * 스케줄러다.
 */
@Service
public class BroadcastStartingSoonService {

    /** 이벤트에서 온 이름이 아니라 알림 종류 이름이다. 화면이 아이콘을 고르는 근거다. */
    static final String EVENT_TYPE = "BroadcastStartingSoon";

    static final Duration LEAD_TIME = Duration.ofMinutes(10);
    private static final String TITLE = "곧 방송이 시작됩니다";
    private static final int BATCH_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(BroadcastStartingSoonService.class);

    private final BroadcastScheduleRepository schedules;
    private final BroadcastAlertRepository alerts;
    private final NotificationWriteRepository notifications;

    public BroadcastStartingSoonService(
            BroadcastScheduleRepository schedules,
            BroadcastAlertRepository alerts,
            NotificationWriteRepository notifications) {
        this.schedules = schedules;
        this.alerts = alerts;
        this.notifications = notifications;
    }

    /**
     * 곧 시작하는 방송을 찾아 신청자에게 알림을 만든다.
     *
     * <p>한 방송이 실패해도 나머지는 계속한다. 한 건 때문에 그 주기 전체가 멈추면 다른 방송의 알림까지 늦어진다.
     *
     * @return 이번 호출로 새로 만들어진 알림 수
     */
    public int notifyStartingSoon(Instant now) {
        List<BroadcastSchedule> startingSoon =
                schedules.findStartingSoon(now, now.plus(LEAD_TIME), BATCH_LIMIT);
        int created = 0;
        for (BroadcastSchedule schedule : startingSoon) {
            try {
                created += notifyOne(schedule, now);
            } catch (RuntimeException e) {
                log.warn("starting soon alert failed broadcastId={}", schedule.broadcastId(), e);
            }
        }
        return created;
    }

    /**
     * 알림을 먼저 만들고, 보냈다는 표시를 나중에 한다.
     *
     * <p>순서가 중요하다. 표시를 먼저 하면 그 뒤 알림 만들기가 실패했을 때 다시 시도하지 못해 알림이 유실된다. 반대로 하면 표시가 실패해도 다음 주기에 다시
     * 시도하고, 이미 만든 알림은 {@code (이벤트ID, 회원ID)} 유일 제약이 걸러 준다.
     */
    private int notifyOne(BroadcastSchedule schedule, Instant now) {
        List<String> memberIds = alerts.findMemberIds(schedule.broadcastId());
        int created = notifications.createAll(toNotification(schedule, now), memberIds);
        schedules.markStartingSoonSent(schedule.broadcastId(), now);
        return created;
    }

    private static NewNotification toNotification(BroadcastSchedule schedule, Instant now) {
        return new NewNotification(
                eventKey(schedule.broadcastId()),
                EVENT_TYPE,
                TITLE,
                body(schedule.title()),
                NotificationLinkType.BROADCAST,
                Long.toString(schedule.broadcastId()),
                // 예정 시각은 아직 오지 않은 시각이다. 그 값을 쓰면 미래 알림이 목록 맨 위에 박힌다.
                now);
    }

    /**
     * 방송마다 늘 같은 값이다. 이벤트에서 온 ID 가 아니라 알림이 만든 키다.
     *
     * <p>일정이 바뀌어 일정 이벤트가 다시 와도 이 키는 그대로여서, 임박 알림은 방송당 한 번만 간다.
     */
    private static String eventKey(long broadcastId) {
        return "broadcast-soon-" + broadcastId;
    }

    private static String body(String broadcastTitle) {
        return "등록하신 '" + broadcastTitle + "' 방송이 " + LEAD_TIME.toMinutes() + "분 뒤 시작됩니다";
    }
}
