package com.shoppinglive.live.broadcast;

import com.shoppinglive.live.security.LiveSecuritySupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.OutboxMessage;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.application.BroadcastStartService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.integration.ivs.IvsConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"live.ivs.mode=stub", "live.ivs.stub-ready=true"})
@DisplayName("방송 라이프사이클 이벤트 (outbox 기록, 실제 전이에서만 1회)")
class BroadcastLifecycleEventTest extends LiveSecuritySupport {
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastProductService links;
    @Autowired BroadcastStartService start;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ObjectMapper objectMapper;

    private Broadcast register(final String name) {
        final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/" + name;
        return broadcasts.register(UUID.randomUUID().toString(),
            new BroadcastInput(name, Instant.parse("2026-01-01T00:00:00Z"), arn,
                IvsConfiguration.stubPlaybackUrl(arn)));
    }

    private long versionOf(final long id) {
        return broadcasts.get(id).getVersion();
    }

    private List<OutboxMessage> eventsOf(final long broadcastId) {
        return outbox.findAll().stream()
            .filter(message -> message.getMessageKey().equals(Long.toString(broadcastId)))
            .sorted(java.util.Comparator.comparing(OutboxMessage::getId))
            .toList();
    }

    @DisplayName("시작과 종료가 각각 한 번씩 같은 토픽·key 로 기록되고, 반복 호출은 기록하지 않는다")
    @Test
    void startAndEndAreRecordedOnceEachOnTheBroadcastTopic() throws Exception {
        final Broadcast broadcast = register("event-lifecycle-" + UUID.randomUUID());
        final long id = broadcast.getId();
        links.link(id, 1L, versionOf(id));

        final Broadcast started = start.start(id, versionOf(id));
        start.start(id, versionOf(id));
        final Broadcast ended = broadcasts.end(id);
        broadcasts.end(id);

        final List<OutboxMessage> events = eventsOf(id);
        assertThat(events).extracting(OutboxMessage::getEventType)
            .containsExactly("LiveBroadcastStarted", "LiveBroadcastEnded");
        assertThat(events).allSatisfy(message -> {
            assertThat(message.getTopic()).isEqualTo("live.broadcast.v1");
            assertThat(message.getPublishedAt()).isNull();
        });

        final JsonNode startedJson = objectMapper.readTree(events.get(0).getPayload());
        assertThat(startedJson.get("eventId").asText()).isEqualTo(events.get(0).getEventId());
        assertThat(startedJson.get("source").asText()).isEqualTo("live-service");
        assertThat(startedJson.get("aggregateId").asText()).isEqualTo(Long.toString(id));
        assertThat(startedJson.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(startedJson.at("/payload/broadcastId").asLong()).isEqualTo(id);
        assertThat(startedJson.at("/payload/broadcastVersion").asLong())
            .isEqualTo(started.getVersion());
        assertThat(startedJson.at("/payload/title").asText()).isEqualTo(broadcast.getTitle());
        assertThat(Instant.parse(startedJson.at("/payload/startedAt").asText()))
            .isEqualTo(started.getStartedAt());
        assertThat(startedJson.at("/payload/productIds/0").asLong()).isEqualTo(1L);

        final JsonNode endedJson = objectMapper.readTree(events.get(1).getPayload());
        assertThat(endedJson.at("/payload/broadcastVersion").asLong())
            .isGreaterThan(started.getVersion()).isEqualTo(ended.getVersion());
        assertThat(Instant.parse(endedJson.at("/payload/endedAt").asText()))
            .isEqualTo(ended.getEndedAt());
        assertThat(Instant.parse(endedJson.get("occurredAt").asText()))
            .isEqualTo(ended.getEndedAt());
    }

    @DisplayName("시작 조건을 못 채워 실패하면 이벤트도 남지 않는다")
    @Test
    void failedStartRecordsNothing() {
        final Broadcast broadcast = register("event-nostart-" + UUID.randomUUID());
        assertThatThrownBy(() -> start.start(broadcast.getId(), versionOf(broadcast.getId())))
            .hasMessageContaining("연결된 상품이 없습니다");
        assertThat(eventsOf(broadcast.getId())).isEmpty();
    }
}
