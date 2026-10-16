package com.shoppinglive.notification.sales.event;

import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.sales.service.RestockNotificationService;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.sales-info.v1} 을 받아 판매 상태 사본을 갱신하고, 품절이 풀리면 재입고 알림을 만든다.
 *
 * <p>Shopping 도 같은 토픽을 구독하지만 consumer group 이 달라서 서로 영향이 없다. 각자 자기 사본을 가진다.
 *
 * <p>실패를 직접 다루지 않는다. 해석과 검증 실패는 {@code InvalidEventMessageException} 으로 올라가 보관함 토픽으로 가고, DB 장애 같은
 * 일시 실패는 복구될 때까지 재시도된다. 분류는 {@code NotificationConsumerConfiguration} 에 한 곳으로 모아 뒀다.
 */
@Component
public class SalesInfoEventListener {

    private final ProductSalesStatusRepository statuses;
    private final RestockNotificationService restockNotifications;

    public SalesInfoEventListener(
            ProductSalesStatusRepository statuses,
            RestockNotificationService restockNotifications) {
        this.statuses = statuses;
        this.restockNotifications = restockNotifications;
    }

    /**
     * 재입고는 <b>품절에서 판매중으로 바뀐 것</b>이다. 이벤트에는 바뀐 뒤의 상태만 담겨 있어서, 갱신 전에 사본의 이전 상태를 먼저 읽어 비교한다.
     *
     * <p><b>알림을 먼저 만들고 사본을 나중에 갱신한다.</b> 순서가 중요하다. 사본을 먼저 갱신하면 그 뒤 알림 만들기가 실패했을 때, 재시도에서는 사본이 이미
     * 판매중이라 전이가 보이지 않는다. 재입고 알림이 그대로 유실된다. 반대로 하면 알림이 실패하면 사본도 품절로 남아 있어서, 재시도에서 전이를 다시 찾아낸다.
     *
     * <p>알림을 두 번 만들 걱정은 없다. 알림을 만든 뒤 갱신이 실패해 재시도되면 전이를 다시 찾지만, {@code (이벤트ID, 회원ID)} 유일 제약이 두 번째를
     * 걸러 준다.
     */
    @KafkaListener(topics = SalesInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        SalesInfoEventMessage message = SalesInfoEventMessage.parse(record.value(), record.key());
        ProductSalesStatus incoming = message.status();
        Optional<ProductSalesStatus> stored = statuses.findByProductId(incoming.productId());
        if (stored.isPresent() && isRestocked(stored.get(), incoming)) {
            restockNotifications.create(
                    message.eventId(), incoming.productId(), message.occurredAt());
        }
        statuses.apply(incoming);
    }

    /**
     * 품절에서 판매중으로 바뀐 것만 재입고다. 상태 값을 그대로 비교한다.
     *
     * <p>"판매 중이 아니었다" 로 보면 안 된다. 판매 준비(READY)나 비공개(PRIVATE)에서 판매 시작으로 바뀐 것까지 재입고가 되어, 품절된 적이 없는 상품에
     * "재입고되었습니다" 가 간다.
     *
     * <p>버전도 본다. 사본보다 옛 이벤트는 이미 지난 상태라서 전이가 아니다. 품절, 판매중, 품절 순으로 바뀐 상품에 가운데 판매중 이벤트가 늦게 도착하면, 지금
     * 품절인 상품에 "재입고되었습니다" 가 간다. 사본 갱신이 0건인 것으로 알아내던 조건인데, 갱신보다 먼저 판단하게 되면서 여기로 옮겼다.
     */
    private static boolean isRestocked(ProductSalesStatus stored, ProductSalesStatus incoming) {
        return stored.snapshotVersion() < incoming.snapshotVersion()
                && SalesInfoEventContract.SOLD_OUT.equals(stored.status())
                && SalesInfoEventContract.ON_SALE.equals(incoming.status());
    }
}
