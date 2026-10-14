package com.shoppinglive.live.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.OutboxMessage;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.promotion.dto.PromotionInput;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.promotion.entity.PromotionStatus;
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
            "spring.datasource.url=${LIVE_PROMOTION_TEST_POSTGRES_URL:jdbc:h2:mem:promotionbroadcastendtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}"
        })
@DisplayName("방송 종료가 OPEN·READY 를 BROADCAST_ENDED 로 끌어 닫고 outbox 순서를 지킨다")
class PromotionBroadcastEndTest extends LiveSecuritySupport {

    @Autowired BroadcastService broadcasts;
    @Autowired PromotionService promotions;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.shoppinglive.live.broadcast.repository.BroadcastRepository broadcastRepository;

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

    private BroadcastPromotion open(long broadcastId) {
        BroadcastPromotion p =
                promotions.create(
                        broadcastId,
                        UUID.randomUUID().toString(),
                        new PromotionInput(
                                "COUPON_DROP", "A-" + UUID.randomUUID(), Map.of(), null));
        return promotions.open(broadcastId, p.getId());
    }

    private BroadcastPromotion ready(long broadcastId) {
        return promotions.create(
                broadcastId,
                UUID.randomUUID().toString(),
                new PromotionInput("TIME_DEAL", "B-" + UUID.randomUUID(), Map.of(), null));
    }

    private List<OutboxMessage> eventsOf(long broadcastId) {
        return outbox.findAll().stream()
                .filter(m -> m.getMessageKey().equals(Long.toString(broadcastId)))
                .filter(m -> !"LiveBroadcastScheduled".equals(m.getEventType()))
                .sorted(java.util.Comparator.comparing(OutboxMessage::getId))
                .toList();
    }

    @DisplayName("OPEN 2 + READY 1 인 방송 종료 → 셋 다 CLOSED, outbox 는 Closed·Closed·Ended 순서")
    @Test
    void endingBroadcastClosesReadyAndOpenAndKeepsOutboxOrder() throws Exception {
        final long id = liveBroadcast();
        final BroadcastPromotion open1 = open(id);
        final BroadcastPromotion open2 = open(id);
        final BroadcastPromotion readyPromotion = ready(id);

        // 종료 전에는 Opened 2건만 있어야 한다
        assertThat(eventsOf(id))
                .extracting(OutboxMessage::getEventType)
                .containsExactly("LiveBroadcastPromotionOpened", "LiveBroadcastPromotionOpened");

        broadcasts.end(id);

        // 종료 후: Opened Opened Closed Closed Ended 순
        final List<OutboxMessage> events = eventsOf(id);
        assertThat(events)
                .extracting(OutboxMessage::getEventType)
                .containsExactly(
                        "LiveBroadcastPromotionOpened",
                        "LiveBroadcastPromotionOpened",
                        "LiveBroadcastPromotionClosed",
                        "LiveBroadcastPromotionClosed",
                        "LiveBroadcastEnded");

        // Closed 두 건의 promotionId 는 open1, open2 (순서는 생성 순)
        final JsonNode firstClosed = mapper.readTree(events.get(2).getPayload()).get("payload");
        final JsonNode secondClosed = mapper.readTree(events.get(3).getPayload()).get("payload");
        assertThat(firstClosed.get("reason").asText()).isEqualTo("BROADCAST_ENDED");
        assertThat(secondClosed.get("reason").asText()).isEqualTo("BROADCAST_ENDED");
        assertThat(
                        List.of(
                                firstClosed.get("promotionId").asLong(),
                                secondClosed.get("promotionId").asLong()))
                .containsExactly(open1.getId(), open2.getId());

        // Ended payload 의 closedPromotionIds 는 OPEN 이었던 둘 — READY 는 들어가지 않는다
        final JsonNode endedPayload = mapper.readTree(events.get(4).getPayload()).get("payload");
        assertThat(endedPayload.at("/closedPromotionIds").isArray()).isTrue();
        assertThat(endedPayload.at("/closedPromotionIds/0").asLong()).isEqualTo(open1.getId());
        assertThat(endedPayload.at("/closedPromotionIds/1").asLong()).isEqualTo(open2.getId());
        assertThat(endedPayload.at("/closedPromotionIds").size()).isEqualTo(2);

        // DB 에서도 셋 다 CLOSED
        assertThat(promotions.list(id))
                .hasSize(3)
                .allMatch(p -> p.getStatus() == PromotionStatus.CLOSED);
        assertThat(
                        promotions.list(id).stream()
                                .filter(p -> p.getId().equals(readyPromotion.getId()))
                                .findFirst()
                                .orElseThrow()
                                .wasOpen())
                .isFalse();
    }

    @Test
    @DisplayName("종료가 방송 잠금을 잡은 동안 생성은 대기하고 종료 커밋 뒤 충돌로 거절된다")
    void creationWaitingForEndCannotLeaveReadyPromotion() throws Exception {
        final long id = liveBroadcast();
        final var locked = new java.util.concurrent.CountDownLatch(1);
        final var releaseEnd = new java.util.concurrent.CountDownLatch(1);
        final var creationStarted = new java.util.concurrent.CountDownLatch(1);
        final var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var end =
                    executor.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        broadcastRepository
                                                                .findForLikeUpdate(id)
                                                                .orElseThrow();
                                                        locked.countDown();
                                                        try {
                                                            if (!releaseEnd.await(
                                                                    10,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS)) {
                                                                throw new IllegalStateException(
                                                                        "생성 대기 확인 시간이 초과되었습니다.");
                                                            }
                                                        } catch (InterruptedException exception) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(
                                                                    exception);
                                                        }
                                                        broadcasts.end(id);
                                                    }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            final var creation =
                    executor.submit(
                            () -> {
                                creationStarted.countDown();
                                return ready(id);
                            });
            assertThat(creationStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 이전 구현은 상태를 잠금 없이 읽고 READY 행을 즉시 삽입하므로 이 대기가 실패한다.
            assertThatThrownBy(() -> creation.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            releaseEnd.countDown();
            end.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> creation.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(com.shoppinglive.common.core.BusinessException.class);
            assertThat(promotions.list(id)).isEmpty();
        } finally {
            releaseEnd.countDown();
            executor.shutdownNow();
        }
    }
}
