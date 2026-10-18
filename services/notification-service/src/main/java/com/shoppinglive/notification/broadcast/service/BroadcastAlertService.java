package com.shoppinglive.notification.broadcast.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.subscription.dto.AlertSubscriptionPage;
import org.springframework.stereotype.Service;

/** 방송 시작 알림 신청과 해제. */
@Service
public class BroadcastAlertService {

    private final BroadcastAlertRepository alerts;
    private final BroadcastScheduleRepository schedules;

    public BroadcastAlertService(
            BroadcastAlertRepository alerts, BroadcastScheduleRepository schedules) {
        this.alerts = alerts;
        this.schedules = schedules;
    }

    public boolean subscribed(String memberId, long targetId) {
        return alerts.exists(memberId, targetId);
    }

    public AlertSubscriptionPage list(String memberId, String cursor, Integer size) {
        int pageSize = AlertSubscriptionPage.pageSize(size);
        return AlertSubscriptionPage.of(
                alerts.list(memberId, AlertSubscriptionPage.cursor(cursor), pageSize + 1),
                pageSize);
    }

    /**
     * 방송 시작 알림을 신청한다. 이미 신청했다면 그대로 성공이다.
     *
     * <p>방송이 있는지는 <b>Live 에 물어보지 않고 우리가 받아 둔 일정 사본</b>으로 확인한다. 물어보는 구조면 Live 가 죽었을 때 신청도 못 받는다.
     *
     * <p>사본에 없다는 것은 그 방송의 일정 이벤트를 아직 못 받았다는 뜻이다. 404 로 답한다 — 받아 두지 않은 방송은 시작 알림도 보낼 수 없으니, 신청을 받아
     * 두고 조용히 안 보내는 것보다 안 된다고 알려주는 쪽이 맞다.
     */
    public void register(String memberId, long broadcastId) {
        if (schedules.findById(broadcastId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        }
        alerts.register(memberId, broadcastId);
    }

    /** 신청을 해제한다. 신청한 적이 없어도 그대로 성공이다 — 원한 상태가 이미 달성돼 있다. */
    public void unregister(String memberId, long broadcastId) {
        alerts.unregister(memberId, broadcastId);
    }
}
