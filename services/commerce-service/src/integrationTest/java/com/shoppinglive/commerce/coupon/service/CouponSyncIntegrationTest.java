package com.shoppinglive.commerce.coupon.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.coupon.dto.CouponDisplaySnapshot;
import com.shoppinglive.commerce.coupon.entity.CouponDefinition;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("쿠폰 표시 이벤트와 동기화 API")
class CouponSyncIntegrationTest extends CommerceSecurityTestSupport {
    @Autowired CouponCreationService creation;
    @Autowired CouponManagementService management;
    @Autowired CouponClaimService claims;
    @Autowired CouponSnapshotService snapshots;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    private final java.util.ArrayList<String> ids = new java.util.ArrayList<>();

    @BeforeAll
    void schema() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (connection.getMetaData().getDatabaseProductName().equals("H2")) {
                var columns =
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_name='COUPON_DEFINITION'");
                if (columns.isEmpty()) {
                    new ResourceDatabasePopulator(
                                    new ClassPathResource(
                                            "db/migration/V5__coupon_definitions.sql"),
                                    new ClassPathResource("db/migration/V6__member_coupons.sql"),
                                    new ClassPathResource(
                                            "db/migration/V17__coupon_display_snapshot_version.sql"))
                            .execute(jdbc.getDataSource());
                }
            }
        }
    }

    @BeforeEach
    void products() {
        shopping.register(new ProductSnapshot(701L, "쿠폰 표시 상품", null, SELLER));
        shopping.register(new ProductSnapshot(702L, "쿠폰 변경 상품", null, SELLER));
    }

    @AfterEach
    void cleanup() {
        for (String id : ids) {
            jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", id);
            jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", id);
            jdbc.update("DELETE FROM coupon_definition WHERE id=?", id);
            jdbc.update("DELETE FROM event_outbox WHERE message_key=?", id);
        }
        ids.clear();
        shopping.clear();
    }

    private CouponDefinition create(int limit) {
        Instant start = Instant.now().plusSeconds(3600), end = start.plusSeconds(3600);
        var definition =
                creation.create(SELLER, "표시 쿠폰", 1000, limit, start, end, end, List.of(701L));
        ids.add(definition.id());
        return definition;
    }

    private List<JsonNode> events(String id) {
        return jdbc
                .queryForList(
                        "SELECT payload FROM event_outbox WHERE message_key=? ORDER BY id",
                        String.class,
                        id)
                .stream()
                .map(
                        value -> {
                            try {
                                return mapper.readTree(value);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                .toList();
    }

    private void activate(String id) {
        jdbc.update(
                "UPDATE coupon_definition SET starts_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                id);
    }

    @Test
    @DisplayName("생성·전체 대상 교체·마지막 발급이 동일한 API 스냅샷을 순차 버전으로 기록한다")
    void completeSnapshotsMatchApiAndOnlyFinalClaimPublishes() throws Exception {
        var coupon = create(2);
        var first = snapshots.get(coupon.id());
        assertThat(first.snapshotVersion()).isEqualTo(1);
        assertThat(first.productIds()).containsExactly(701L);
        Instant now = Instant.now();
        management.update(
                SELLER,
                coupon.id(),
                0,
                "변경 쿠폰",
                500,
                2,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                now.plusSeconds(3600),
                List.of(702L));
        assertThat(snapshots.get(coupon.id()).productIds()).containsExactly(702L);
        claims.claim(MEMBER_A, coupon.id());
        assertThat(events(coupon.id())).hasSize(2);
        claims.claim(MEMBER_B, coupon.id());
        assertThat(claims.claim(MEMBER_B, coupon.id()).created()).isFalse();
        assertThatThrownBy(() -> claims.claim(UUID.randomUUID().toString(), coupon.id()))
                .isInstanceOf(BusinessException.class);
        var events = events(coupon.id());
        assertThat(events).hasSize(3);
        assertThat(events.stream().map(e -> e.get("eventType").asText()))
                .containsExactly("CouponCreated", "CouponUpdated", "CouponSoldOut");
        assertThat(events.stream().map(e -> e.at("/payload/snapshotVersion").asLong()))
                .containsExactly(1L, 2L, 3L);
        var last = events.getLast();
        assertThat(last.get("source").asText()).isEqualTo("commerce-service");
        assertThat(last.get("aggregateId").asText()).isEqualTo(coupon.id());
        assertThat(last.at("/payload/soldOut").asBoolean()).isTrue();
        assertThat(last.get("payload"))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(snapshots.get(coupon.id()))));
        mvc.perform(
                        get("/v1/internal/coupon-snapshots/" + coupon.id())
                                .header("X-Service-Token", SHOPPING_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotVersion").value(3))
                .andExpect(jsonPath("$.data.productIds[0]").value(702));
        var directory = java.nio.file.Path.of("build/contract-samples");
        java.nio.file.Files.createDirectories(directory);
        for (var event : events)
            mapper.writeValue(
                    directory.resolve(event.get("eventType").asText() + "-actual.json").toFile(),
                    java.util.Map.of(
                            "contract",
                            "commerce-coupon-info.yaml",
                            "message",
                            event.get("eventType").asText(),
                            "payload",
                            event));
    }

    @Test
    @DisplayName("쿠폰 생성·최종 발급 롤백은 표시 버전과 Outbox도 함께 롤백한다")
    void rollbacksLeaveNeitherMutationNorEvent() {
        var transaction = new TransactionTemplate(manager);
        String[] rolledBack = new String[1];
        transaction.executeWithoutResult(
                status -> {
                    rolledBack[0] = create(1).id();
                    status.setRollbackOnly();
                });
        assertThat(events(rolledBack[0])).isEmpty();
        assertThatThrownBy(() -> snapshots.get(rolledBack[0]))
                .isInstanceOf(BusinessException.class);
        var coupon = create(1);
        activate(coupon.id());
        transaction.executeWithoutResult(
                status -> {
                    claims.claim(MEMBER_A, coupon.id());
                    status.setRollbackOnly();
                });
        assertThat(events(coupon.id())).hasSize(1);
        assertThat(snapshots.get(coupon.id()).snapshotVersion()).isEqualTo(1);
        assertThat(snapshots.get(coupon.id()).soldOut()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id=?",
                                Integer.class,
                                coupon.id()))
                .isZero();
    }

    @Test
    @DisplayName("수량 1개를 동시에 발급해도 소진 이벤트는 한 건이며 같은 버전으로 조회된다")
    void concurrentFinalClaimsWriteOneSoldOutEvent() throws Exception {
        var coupon = create(1);
        activate(coupon.id());
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    i ->
                                            executor.submit(
                                                    () -> {
                                                        go.await();
                                                        try {
                                                            claims.claim(
                                                                    "sync-claim-" + i, coupon.id());
                                                            return true;
                                                        } catch (BusinessException expected) {
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            go.countDown();
            int successful = 0;
            for (var future : futures)
                if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
        }
        assertThat(events(coupon.id())).hasSize(2);
        assertThat(events(coupon.id()).getLast().get("eventType").asText())
                .isEqualTo("CouponSoldOut");
        assertThat(snapshots.get(coupon.id()).snapshotVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("동기화 API는 Shopping 토큰만 허용하고 페이지·ID 입력을 검사한다")
    void authenticationAndInputContract() throws Exception {
        String path = "/v1/internal/coupon-snapshots";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token", LIVE_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Service-Token", "invalid"))
                .andExpect(status().isUnauthorized());
        for (String value : List.of("0", "101", "abc")) {
            mvc.perform(get(path).param("size", value).header("X-Service-Token", SHOPPING_TOKEN))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).param("afterId", "").header("X-Service-Token", SHOPPING_TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path + "/" + "x".repeat(37)).header("X-Service-Token", SHOPPING_TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path + "/missing-opaque").header("X-Service-Token", SHOPPING_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("목록은 미래·진행·소진 쿠폰을 포함하고 종료 쿠폰은 단건으로 복구한다")
    void paginatedInitialLoadIncludesFutureAndSoldOutButDetailIncludesEnded() {
        String prefix = "zz-sync-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        for (int i = 1; i <= 3; i++) {
            String id = prefix + i;
            ids.add(id);
            jdbc.update(
                    "INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,created_at) VALUES(?,?,'조회 쿠폰',100,1,?,?,?,?,?)",
                    id,
                    SELLER,
                    i == 2 ? 1 : 0,
                    Timestamp.from(i == 1 ? now.plusSeconds(60) : now.minusSeconds(60)),
                    Timestamp.from(i == 3 ? now.minusSeconds(1) : now.plusSeconds(120)),
                    Timestamp.from(now.plusSeconds(120)),
                    Timestamp.from(now));
        }
        var page = snapshots.list(prefix, 1);
        assertThat(page.items())
                .extracting(CouponDisplaySnapshot::couponId)
                .containsExactly(prefix + "1");
        assertThat(page.hasNext()).isTrue();
        var next = snapshots.list(page.nextCursor(), 1);
        assertThat(next.items())
                .extracting(CouponDisplaySnapshot::couponId)
                .containsExactly(prefix + "2");
        assertThat(next.items().getFirst().soldOut()).isTrue();
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThat(snapshots.get(prefix + "3").endsAt()).isBefore(now);
        assertThat(snapshots.get(prefix + "3").productIds()).isEmpty();
    }
}
