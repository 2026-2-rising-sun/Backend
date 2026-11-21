package com.shoppinglive.payment.refund.event;

import com.shoppinglive.payment.refund.service.RefundIntakeService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Commerce 환불·시스템 보상 요청을 받는다. 실패 처리는 {@link RefundConsumerConfiguration}이 맡는다. */
@Component
@RequiredArgsConstructor
class RefundRequestListener {
    static final String TOPIC = "commerce.refund.requests.v1";

    private final RefundIntakeService intake;

    @KafkaListener(
            topics = TOPIC,
            groupId = "payment-refund-requests-v1",
            autoStartup = "${payment.refunds.consumer-enabled:true}")
    void receive(ConsumerRecord<String, String> record) {
        // 접수 트랜잭션이 커밋된 뒤에 실행을 시작한다(토스 호출은 트랜잭션 밖)
        intake.receive(RefundRequestParser.parse(record.key(), record.value()))
                .ifPresent(intake::execute);
    }
}
