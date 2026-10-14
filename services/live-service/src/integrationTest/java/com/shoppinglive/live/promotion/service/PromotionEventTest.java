package com.shoppinglive.live.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.OutboxMessage;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.promotion.dto.PromotionInput;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spring.datasource.driver-class-name=${LIVE_PROMOTION_TEST_DB_DRIVER:org.h2.Driver}",
            "spring.datasource.username=${LIVE_PROMOTION_TEST_DB_USER:sa}",
            "spring.datasource.password=${LIVE_PROMOTION_TEST_DB_PASSWORD:}",
            "spring.datasource.url=${LIVE_PROMOTION_TEST_POSTGRES_URL:jdbc:h2:mem:promotioneventtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}"
        })
@DisplayName("프로모션 outbox 기록 (실제 전이에서만 발행, payload 검증)")
class PromotionEventTest extends LiveSecuritySupport {

    @Autowired BroadcastService broadcasts;
    @Autowired PromotionService promotions;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private long liveBroadcast() {
        final String name = UUID.randomUUID().toString();
        final long id =
                broadcasts
                        .register(
                                name,
                                new BroadcastInput(
                                        name,
                                        Instant.now(),
                                        "arn:aws:ivs:channel/" + name,
                                        "https://example.live-video.net/" + name))
                        .getId();
        jdbc.update(
                "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
        return id;
    }

    private List<OutboxMessage> eventsOf(long broadcastId) {
        return outbox.findAll().stream()
                .filter(m -> m.getMessageKey().equals(Long.toString(broadcastId)))
                .filter(m -> !"LiveBroadcastScheduled".equals(m.getEventType()))
                .sorted(java.util.Comparator.comparing(OutboxMessage::getId))
                .toList();
    }

    @DisplayName("READY 생성·반복 열기·반복 닫기·READY 취소에는 outbox 기록이 없다")
    @Test
    void createRepeatOpenRepeatCloseAndReadyCancelDoNotPublish() {
        final long id = liveBroadcast();
        final BroadcastPromotion a =
                promotions.create(
                        id,
                        UUID.randomUUID().toString(),
                        new PromotionInput("COUPON_DROP", "A", Map.of(), null));
        assertThat(eventsOf(id)).isEmpty();

        promotions.open(id, a.getId());
        promotions.open(id, a.getId()); // idempotent
        final List<OutboxMessage> afterOpenOpen = eventsOf(id);
        assertThat(afterOpenOpen)
                .hasSize(1)
                .extracting(OutboxMessage::getEventType)
                .containsExactly("LiveBroadcastPromotionOpened");

        promotions.close(id, a.getId());
        promotions.close(id, a.getId()); // idempotent
        final List<OutboxMessage> afterCloseClose = eventsOf(id);
        assertThat(afterCloseClose)
                .extracting(OutboxMessage::getEventType)
                .containsExactly("LiveBroadcastPromotionOpened", "LiveBroadcastPromotionClosed");

        // READY 취소는 발행 없음
        final BroadcastPromotion b =
                promotions.create(
                        id,
                        UUID.randomUUID().toString(),
                        new PromotionInput("TIME_DEAL", "B", Map.of(), null));
        promotions.close(id, b.getId());
        assertThat(eventsOf(id)).hasSize(2); // 그대로 2건
    }

    @DisplayName("Opened payload 는 attributes 를 정렬된 JSON 으로 담고, promotionVersion 은 전이 후 값")
    @Test
    void openedPayloadCarriesSortedAttributesAndTransitionedVersion() throws Exception {
        final long id = liveBroadcast();
        final BroadcastPromotion created =
                promotions.create(
                        id,
                        UUID.randomUUID().toString(),
                        new PromotionInput(
                                "COUPON_DROP",
                                "할인",
                                Map.of("couponPolicyId", 7, "claimLimit", 500),
                                300));
        final BroadcastPromotion opened = promotions.open(id, created.getId());

        List<OutboxMessage> events = eventsOf(id);
        assertThat(events).hasSize(1);
        OutboxMessage openedMessage = events.get(0);
        assertThat(openedMessage.getTopic()).isEqualTo("live.broadcast.v1");
        assertThat(openedMessage.getEventType()).isEqualTo("LiveBroadcastPromotionOpened");
        assertThat(openedMessage.getPublishedAt()).isNull();

        JsonNode json = mapper.readTree(openedMessage.getPayload());
        assertThat(json.get("aggregateId").asText()).isEqualTo(Long.toString(id));
        assertThat(json.at("/payload/broadcastId").asLong()).isEqualTo(id);
        assertThat(json.at("/payload/promotionId").asLong()).isEqualTo(opened.getId());
        assertThat(json.at("/payload/promotionVersion").asLong()).isEqualTo(opened.getVersion());
        assertThat(json.at("/payload/type").asText()).isEqualTo("COUPON_DROP");
        assertThat(json.at("/payload/attributes/claimLimit").asInt()).isEqualTo(500);
        assertThat(json.at("/payload/attributes/couponPolicyId").asInt()).isEqualTo(7);
    }
}
