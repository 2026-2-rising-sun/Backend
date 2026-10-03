package com.shoppinglive.live.broadcast;

import com.shoppinglive.live.security.LiveSecuritySupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.live.chat.api.ChatMessageResponse;
import com.shoppinglive.live.chat.application.ChatQueryService;
import com.shoppinglive.live.chat.domain.BroadcastChat;
import com.shoppinglive.live.chat.infrastructure.BroadcastChatRepository;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 PostgreSQL 에 Flyway migration 을 적용하고 DB 불변조건을 SQL 로 직접 검증한다.
 * H2 통과는 이 검증의 증거가 되지 못한다. 실행 방법은 application-postgres.yml 주석 참고.
 */
@SpringBootTest
@ActiveProfiles({"test", "postgres"})
@EnabledIfEnvironmentVariable(named = "LIVE_PG_TEST", matches = "1")
@DisplayName("실제 PostgreSQL에서 Flyway 마이그레이션과 DB 불변조건 검증")
class PostgresMigrationTest extends LiveSecuritySupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ChatQueryService chatQueries;

    @Autowired
    BroadcastChatRepository chats;

    @Autowired
    com.shoppinglive.live.like.application.LikeSnapshotService likeSnapshots;

    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE broadcast CASCADE");
    }

    @DisplayName("Flyway 마이그레이션이 적용되어 broadcast 테이블이 생성된다")
    @Test
    void migrationIsApplied() {
        final Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'broadcast'",
            Integer.class)).isEqualTo(1);
    }

    @DisplayName("같은 request_key를 중복 INSERT하면 유니크 제약에 걸린다")
    @Test
    void duplicateIdempotencyKeyViolatesUniqueConstraint() {
        insertBroadcast("dup-key", "arn:aws:ivs:channel/a", "PREPARING");
        assertThatThrownBy(() -> insertBroadcast("dup-key", "arn:aws:ivs:channel/b", "PREPARING"))
            .hasMessageContaining("broadcast_request_key_key");
    }

    @Test
    void duplicateProductOnSameBroadcastViolatesUniqueConstraint() {
        insertBroadcast("bp-key", "arn:aws:ivs:channel/bp", "PREPARING");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'bp-key'", Long.class);
        insertLink(broadcastId, 7L, 0);
        assertThatThrownBy(() -> insertLink(broadcastId, 7L, 1))
            .hasMessageContaining("uk_broadcast_product");
    }

    @Test
    void twoBroadcastsCannotBeLiveOnTheSameChannel() {
        final String channel = "arn:aws:ivs:ap-northeast-2:1:channel/shared";
        insertBroadcast("live-a", channel, "LIVE");
        // 같은 채널의 PREPARING/ENDED 는 얼마든지 공존한다.
        insertBroadcast("prep-b", channel, "PREPARING");
        insertBroadcast("ended-c", channel, "ENDED");

        assertThatThrownBy(() -> insertBroadcast("live-b", channel, "LIVE"))
            .hasMessageContaining("uk_broadcast_live_channel");
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE broadcast SET status = 'LIVE' WHERE request_key = 'prep-b'"))
            .hasMessageContaining("uk_broadcast_live_channel");
    }

    @DisplayName("V4가 채팅 테이블과 최근 조회 인덱스를 만든다")
    @Test
    void chatMigrationCreatesTableAndRecentIndex() {
        assertThat(jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_broadcast_chat_recent'", String.class))
            .contains("broadcast_id, created_at DESC, id DESC");
    }

    @DisplayName("없는 방송의 채팅은 FK 제약에 걸린다")
    @Test
    void chatRequiresExistingBroadcast() {
        assertThatThrownBy(() -> insertChat(Long.MAX_VALUE, "orphan", Instant.now()))
            .hasMessageContaining("fk_broadcast_chat_broadcast");
    }

    @DisplayName("실제 PostgreSQL에서 최신 50건을 오래된 순서로, 같은 시각은 id 순서로 조회한다")
    @Test
    void recentChatsAreLatestFiftyInStableOrder() {
        insertBroadcast("chat-key", "arn:aws:ivs:channel/chat", "LIVE");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'chat-key'", Long.class);
        final Instant base = Instant.parse("2026-10-03T11:00:00Z");
        insertChat(broadcastId, "dropped", base);
        for (int i = 1; i <= 48; i++) {
            insertChat(broadcastId, "m" + i, base.plusSeconds(i));
        }
        insertChat(broadcastId, "tie-a", base.plusSeconds(100));
        insertChat(broadcastId, "tie-b", base.plusSeconds(100));

        final List<String> contents = chatQueries.recent(broadcastId).stream()
            .map(ChatMessageResponse::content).toList();

        assertThat(contents).hasSize(50).startsWith("m1").endsWith("tie-a", "tie-b").doesNotContain("dropped");
    }

    @DisplayName("저장한 채팅의 이모지 200자와 마이크로초 작성 시각이 재조회에서도 같다")
    @Test
    void savedChatRoundTripsEmojiAndMicrosecondTimestamp() {
        insertBroadcast("chat-save", "arn:aws:ivs:channel/chat-save", "LIVE");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'chat-save'", Long.class);
        final Instant createdAt = Instant.parse("2026-10-03T11:00:00.123456789Z").truncatedTo(ChronoUnit.MICROS);
        final String content = "😀".repeat(200);

        chats.saveAndFlush(new BroadcastChat(broadcastId, UUID.randomUUID(), "회원", content, createdAt));

        final ChatMessageResponse saved = chatQueries.recent(broadcastId).getFirst();
        assertThat(saved.content()).isEqualTo(content);
        assertThat(saved.createdAt()).isEqualTo(createdAt);
    }

    @DisplayName("좋아요 보관본은 방송당 한 행이고 없는 방송과 음수 합계를 거부한다")
    @Test
    void likeSnapshotIsOnePerBroadcastAndNonNegative() {
        insertBroadcast("like-snapshot", "arn:aws:ivs:channel/like-snapshot", "LIVE");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'like-snapshot'", Long.class);
        final String insert =
            "INSERT INTO broadcast_like_snapshot (broadcast_id, total, updated_at) VALUES (?, ?, now())";

        jdbc.update(insert, broadcastId, 0L);

        assertThatThrownBy(() -> jdbc.update(insert, broadcastId, 1L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, Long.MAX_VALUE, 1L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE broadcast_like_snapshot SET total = -1 WHERE broadcast_id = ?",
            broadcastId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("좋아요 보관은 여러 번·동시에 실행해도 더 큰 값만 남긴다")
    @Test
    void likeSnapshotStoreKeepsTheLargestTotal() throws Exception {
        insertBroadcast("like-store", "arn:aws:ivs:channel/like-store", "LIVE");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'like-store'", Long.class);
        final String select = "SELECT total FROM broadcast_like_snapshot WHERE broadcast_id = ?";

        likeSnapshots.store(broadcastId, 10);
        likeSnapshots.store(broadcastId, 5);
        likeSnapshots.store(broadcastId, 10);
        assertThat(jdbc.queryForObject(select, Long.class, broadcastId)).isEqualTo(10);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (long total = 1; total <= 200; total++) {
                final long value = total;
                pool.submit(() -> likeSnapshots.store(broadcastId, value));
            }
        }
        assertThat(jdbc.queryForObject(select, Long.class, broadcastId)).isEqualTo(200);
    }

    @DisplayName("같은 순간에 여러 pod 가 첫 보관을 해도 실패하지 않고 한 행만 남는다")
    @Test
    void concurrentFirstLikeSnapshotsLeaveOneRow() throws Exception {
        insertBroadcast("like-first", "arn:aws:ivs:channel/like-first", "LIVE");
        final Long broadcastId = jdbc.queryForObject(
            "SELECT id FROM broadcast WHERE request_key = 'like-first'", Long.class);
        final List<java.util.concurrent.Future<?>> results = new java.util.ArrayList<>();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int attempt = 0; attempt < 8; attempt++) {
                results.add(pool.submit(() -> likeSnapshots.store(broadcastId, 7)));
            }
        }

        for (final var result : results) {
            result.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_like_snapshot WHERE broadcast_id = ?",
            Integer.class, broadcastId)).isEqualTo(1);
    }

    void insertChat(final Long broadcastId, final String content, final Instant createdAt) {
        jdbc.update("""
            INSERT INTO broadcast_chat (broadcast_id, member_id, display_name, content, created_at)
            VALUES (?, ?, '회원', ?, ?)
            """, broadcastId, UUID.randomUUID(), content, Timestamp.from(createdAt));
    }

    void insertLink(final Long broadcastId, final long productId, final int position) {
        jdbc.update("""
            INSERT INTO broadcast_product (created_at, updated_at, broadcast_id, product_id,
                sales_id, position)
            VALUES (now(), now(), ?, ?, ?, ?)
            """, broadcastId, productId, productId + 100, position);
    }

    void insertBroadcast(final String requestKey, final String channelArn, final String status) {
        jdbc.update("""
            INSERT INTO broadcast (created_at, updated_at, request_key, fingerprint, title,
                scheduled_at, channel_arn, playback_url, status, version)
            VALUES (now(), now(), ?, 'fp', 'title', now(), ?, 'https://example/p.m3u8', ?, 0)
            """, requestKey, channelArn, status);
    }
}
