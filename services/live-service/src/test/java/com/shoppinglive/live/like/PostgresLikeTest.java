package com.shoppinglive.live.like;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.like.application.LikeService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"test", "postgres"})
@EnabledIfEnvironmentVariable(named = "LIVE_PG_TEST", matches = "1")
class PostgresLikeTest extends LiveSecuritySupport {
    @Autowired LikeService likes;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;

    private long live() {
        final String name = UUID.randomUUID().toString();
        final long id = broadcasts.register(name, new BroadcastInput(name, Instant.now(),
            "arn:aws:ivs:channel/" + name, "https://example.com/" + name)).getId();
        jdbc.update("UPDATE broadcast SET status = 'LIVE' WHERE id = ?", id);
        return id;
    }

    private void concurrent(List<Callable<Void>> work) throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            for (var result : pool.invokeAll(work)) result.get();
        }
    }

    @Test void concurrentSameKeyAndDistinctKeysCountOneMemberOnce() throws Exception {
        final long id = live();
        final String member = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
        final List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) work.add(() -> { likes.set(id, member, key, true); return null; });
        concurrent(work);
        assertThat(likes.total(id).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_like_request WHERE broadcast_id = ?", Long.class, id)).isEqualTo(1);
        work.clear();
        for (int i = 0; i < 100; i++) work.add(() -> { likes.set(id, member, UUID.randomUUID().toString(), true); return null; });
        concurrent(work);
        assertThat(likes.total(id).total()).isEqualTo(1);
        assertThat(likes.total(id).version()).isEqualTo(1);
        assertThat(likes.mine(id, member).stateVersion()).isEqualTo(1);
    }

    @Test void concurrentDistinctMembersAndCancelsKeepExactAggregate() throws Exception {
        final long id = live();
        final List<String> members = new ArrayList<>();
        final List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final String member = UUID.randomUUID().toString(); members.add(member);
            work.add(() -> { likes.set(id, member, UUID.randomUUID().toString(), true); return null; });
        }
        concurrent(work);
        assertThat(likes.total(id).total()).isEqualTo(100);
        work.clear();
        for (String member : members) work.add(() -> { likes.set(id, member, UUID.randomUUID().toString(), false); return null; });
        concurrent(work);
        assertThat(likes.total(id).total()).isZero();
        assertThat(likes.total(id).version()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_member_like WHERE broadcast_id = ? AND liked", Long.class, id)).isZero();
    }

    @Test void oldReplayNeverRestoresCancelledLikeAndKeyScopeIsIndependent() {
        final long id = live(), other = live();
        final String member = UUID.randomUUID().toString(), another = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
        final var original = likes.set(id, member, key, true);
        likes.set(id, member, UUID.randomUUID().toString(), false);
        assertThat(likes.set(id, member, key, true)).isEqualTo(original);
        assertThat(likes.mine(id, member).liked()).isFalse();
        assertThatThrownBy(() -> likes.set(id, member, key, false)).isInstanceOf(BusinessException.class);
        likes.set(id, another, key, true);
        likes.set(other, member, key, true);
        assertThat(likes.total(id).total()).isEqualTo(1);
        assertThat(likes.total(other).total()).isEqualTo(1);
    }

    @Test void failedLedgerWriteRollsBackTheStateAndAggregate() {
        final long id = live();
        final String member = UUID.randomUUID().toString();
        // Fail the final transaction step after both member state and aggregate were changed.
        jdbc.execute("ALTER TABLE broadcast_like_request ADD CONSTRAINT test_reject_like_ledger CHECK (broadcast_id <> " + id + ") NOT VALID");
        try {
            assertThatThrownBy(() -> likes.set(id, member, UUID.randomUUID().toString(), true))
                .isInstanceOf(BusinessException.class);
            assertThat(likes.total(id).total()).isZero();
            assertThat(likes.total(id).version()).isZero();
            assertThat(likes.mine(id, member).liked()).isFalse();
            assertThat(likes.mine(id, member).stateVersion()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_like_request WHERE broadcast_id = ?", Long.class, id)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE broadcast_like_request DROP CONSTRAINT test_reject_like_ledger");
        }
    }

    @Test void endedBroadcastAllowsOnlyRecordedResults() throws Exception {
        final long id = live();
        final String member = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
        final var original = likes.set(id, member, key, true);
        final List<Callable<Void>> work = List.of(
            () -> { broadcasts.end(id); return null; },
            () -> { try { likes.set(id, member, UUID.randomUUID().toString(), false); } catch (BusinessException expected) {} return null; });
        concurrent(work);
        final var finalState = likes.mine(id, member);
        assertThat(likes.set(id, member, key, true)).isEqualTo(original);
        assertThatThrownBy(() -> likes.set(id, member, UUID.randomUUID().toString(), !finalState.liked()))
            .isInstanceOf(BusinessException.class);
        assertThat(likes.mine(id, member)).isEqualTo(finalState);
        assertThat(likes.total(id).total()).isEqualTo(finalState.liked() ? 1 : 0);
    }
}
