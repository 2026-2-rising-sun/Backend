package com.shoppinglive.notification.sales.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.event.SalesInfoEventContract;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import org.springframework.stereotype.Service;

/** 재입고 알림 신청과 해제. */
@Service
public class RestockAlertService {

    private final RestockAlertRepository alerts;
    private final ProductSalesStatusRepository statuses;

    public RestockAlertService(
            RestockAlertRepository alerts, ProductSalesStatusRepository statuses) {
        this.alerts = alerts;
        this.statuses = statuses;
    }

    /**
     * 재입고 알림을 신청한다. 이미 신청했다면 그대로 성공이다.
     *
     * <p>판매 상태는 Commerce 에 묻지 않고 <b>우리가 받아 둔 사본</b>으로 본다. 묻는 구조면 Commerce 가 죽었을 때 신청도 못 받는다.
     *
     * <p>사본에 없으면 404 다. 판매정보 이벤트를 아직 못 받은 상품이고, 받아 두지 않은 상품은 재입고도 알아챌 수 없다.
     *
     * <p>품절이 아니면 409 다. 재입고 알림은 품절을 전제한다. "판매 중이 아니면 통과" 로 보면 안 된다. 판매 준비(READY)나 비공개(PRIVATE) 상품에도
     * 신청이 되는데, 그 상품은 품절에서 판매중으로 바뀌는 전이를 거치지 않아 알림이 영원히 가지 않는다.
     */
    public void register(String memberId, long productId) {
        ProductSalesStatus status =
                statuses.findByProductId(productId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다."));
        if (!SalesInfoEventContract.SOLD_OUT.equals(status.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "품절 상품만 재입고 알림을 신청할 수 있습니다.");
        }
        alerts.register(memberId, productId);
    }

    /** 신청을 해제한다. 신청한 적이 없어도 그대로 성공이다. */
    public void unregister(String memberId, long productId) {
        alerts.unregister(memberId, productId);
    }
}
