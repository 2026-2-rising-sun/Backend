package com.shoppinglive.live.broadcast;

import com.shoppinglive.live.security.LiveSecuritySupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.OutboxMessage;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastPatchInput;
import com.shoppinglive.live.broadcast.application.BroadcastProductService;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.application.BroadcastStartService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.integration.ivs.IvsConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
            .containsExactly("LiveBroadcastScheduled", "LiveBroadcastStarted", "LiveBroadcastEnded");
        assertThat(events).allSatisfy(message -> {
            assertThat(message.getTopic()).isEqualTo("live.broadcast.v1");
            assertThat(message.getPublishedAt()).isNull();
        });

        final JsonNode startedJson = objectMapper.readTree(events.get(1).getPayload());
        assertThat(startedJson.get("eventId").asText()).isEqualTo(events.get(1).getEventId());
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

        final JsonNode endedJson = objectMapper.readTree(events.get(2).getPayload());
        assertThat(endedJson.at("/payload/broadcastVersion").asLong())
            .isGreaterThan(started.getVersion()).isEqualTo(ended.getVersion());
        assertThat(Instant.parse(endedJson.at("/payload/endedAt").asText()))
            .isEqualTo(ended.getEndedAt());
        assertThat(Instant.parse(endedJson.get("occurredAt").asText()))
            .isEqualTo(ended.getEndedAt());
    }

    @DisplayName("시작 조건을 못 채워 실패해도 등록 시 발행된 일정 이벤트는 남아 있다(시작·종료 이벤트는 없다)")
    @Test
    void failedStartRecordsNothing() {
        final Broadcast broadcast = register("event-nostart-" + UUID.randomUUID());
        assertThatThrownBy(() -> start.start(broadcast.getId(), versionOf(broadcast.getId())))
            .hasMessageContaining("연결된 상품이 없습니다");
        assertThat(eventsOf(broadcast.getId())).extracting(OutboxMessage::getEventType)
            .containsExactly("LiveBroadcastScheduled");
    }

    @DisplayName("등록은 알림이 시작 임박 알림을 계산할 수 있도록 일정 이벤트를 한 번 남기고, 같은 키 재요청은 남기지 않는다")
    @Test
    void registrationRecordsTheScheduleOnce() throws Exception {
        final String key = UUID.randomUUID().toString();
        final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/event-scheduled-" + key;
        final BroadcastInput input = new BroadcastInput("가을 신상 라이브",
            Instant.parse("2026-11-01T11:00:00Z"), arn, IvsConfiguration.stubPlaybackUrl(arn));

        final Broadcast registered = broadcasts.register(key, input);
        broadcasts.register(key, input);

        final List<OutboxMessage> events = eventsOf(registered.getId());
        assertThat(events).extracting(OutboxMessage::getEventType)
            .containsExactly("LiveBroadcastScheduled");
        final JsonNode json = objectMapper.readTree(events.get(0).getPayload());
        assertThat(json.at("/payload/broadcastId").asLong()).isEqualTo(registered.getId());
        assertThat(json.at("/payload/broadcastVersion").asLong()).isEqualTo(registered.getVersion());
        assertThat(json.at("/payload/title").asText()).isEqualTo("가을 신상 라이브");
        assertThat(Instant.parse(json.at("/payload/scheduledAt").asText()))
            .isEqualTo(Instant.parse("2026-11-01T11:00:00Z"));
    }

    @DisplayName("제목이나 예정 시각을 바꾸면 일정이 다시 기록되고, 송출 정보만 바꾸면 기록하지 않는다")
    @Test
    void scheduleChangesAreRecordedButStreamingChangesAreNot() throws Exception {
        final Broadcast broadcast = register("event-reschedule-" + UUID.randomUUID());
        final long id = broadcast.getId();
        final Instant moved = Instant.parse("2026-01-02T09:30:00Z");

        broadcasts.edit(id, versionOf(id),
            new BroadcastPatchInput(null, Optional.of(moved), null, null));
        final String arn = "arn:aws:ivs:ap-northeast-2:1:channel/moved-" + id;
        broadcasts.edit(id, versionOf(id), new BroadcastPatchInput(null, null,
            Optional.of(arn), Optional.of(IvsConfiguration.stubPlaybackUrl(arn))));

        final List<OutboxMessage> events = eventsOf(id);
        assertThat(events).extracting(OutboxMessage::getEventType)
            .containsExactly("LiveBroadcastScheduled", "LiveBroadcastScheduled");
        final JsonNode latest = objectMapper.readTree(events.get(1).getPayload());
        assertThat(Instant.parse(latest.at("/payload/scheduledAt").asText())).isEqualTo(moved);
        assertThat(latest.at("/payload/broadcastVersion").asLong())
            .isGreaterThan(objectMapper.readTree(events.get(0).getPayload())
                .at("/payload/broadcastVersion").asLong());
    }
}
