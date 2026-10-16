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
     * <p>사본이 갱신되지 않았다면(중복이나 역순 이벤트) 알림도 만들지 않는다. 같은 이벤트를 두 번 받아도 두 번째는 갱신이 0건으로 끝난다.
     */
    @KafkaListener(topics = SalesInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        SalesInfoEventMessage message = SalesInfoEventMessage.parse(record.value(), record.key());
        ProductSalesStatus incoming = message.status();
        Optional<ProductSalesStatus> stored = statuses.findByProductId(incoming.productId());
        if (!statuses.apply(incoming)) {
            return;
        }
        if (isRestocked(stored, incoming)) {
            restockNotifications.create(
                    message.eventId(),
                    incoming.productId(),
                    message.occurredAt(),
                    message.productName());
        }
    }

    /** 사본에 없던 상품은 재입고가 아니다. 품절이었던 적을 본 적이 없으니 신청자도 없다. */
    private static boolean isRestocked(
            Optional<ProductSalesStatus> stored, ProductSalesStatus incoming) {
        return stored.isPresent() && !stored.get().inStock() && incoming.inStock();
    }
}
