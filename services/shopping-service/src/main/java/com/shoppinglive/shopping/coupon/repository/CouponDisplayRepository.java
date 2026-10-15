package com.shoppinglive.shopping.coupon.repository;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.dto.ProductCouponResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commerce 쿠폰 표시정보의 replica({@code coupon_display}, {@code coupon_display_product})를 읽고 쓴다.
 *
 * <p>쿠폰 이벤트는 중복되거나 순서가 바뀌어 올 수 있다. 그래서 저장된 값보다 큰 {@code snapshotVersion} 만 반영한다. 이벤트와 동기화 API 응답이
 * 같은 규칙을 탄다.
 *
 * <p>판매정보 replica 와 달리 한 transaction 에서 쓴다. 쿠폰 행과 대상 상품 연결이 함께 바뀌어야, 조회 중에 "새 이름 + 옛 대상 상품" 같은 반쯤
 * 바뀐 값이 보이지 않는다.
 */
@Repository
public class CouponDisplayRepository {

    private static final String UPDATE_IF_NEWER =
            """
        UPDATE coupon_display
        SET name = :name, fixed_discount = :fixedDiscount, starts_at = :startsAt, ends_at = :endsAt,
            expires_at = :expiresAt, sold_out = :soldOut, version = :version, updated_at = :updatedAt
        WHERE coupon_id = :couponId AND version < :version
        """;
    private static final String INSERT_IF_MISSING =
            """
        INSERT INTO coupon_display
            (coupon_id, name, fixed_discount, starts_at, ends_at, expires_at, sold_out, version, updated_at)
        SELECT :couponId, :name, :fixedDiscount, :startsAt, :endsAt, :expiresAt, :soldOut, :version, :updatedAt
        WHERE NOT EXISTS (SELECT 1 FROM coupon_display WHERE coupon_id = :couponId)
        """;
    private static final String FIND_ACTIVE_BY_PRODUCT =
            """
        SELECT c.coupon_id, c.name, c.fixed_discount, c.starts_at, c.ends_at, c.expires_at, c.sold_out
        FROM coupon_display c
        JOIN coupon_display_product p ON p.coupon_id = c.coupon_id
        WHERE p.product_id = :productId AND c.starts_at <= :now AND c.ends_at > :now
        ORDER BY c.ends_at, c.coupon_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CouponDisplayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 더 큰 version 이면 쿠폰 값과 대상 상품 연결을 통째로 바꾼다. 같거나 작은 version 은 아무것도 바꾸지 않는다.
     *
     * <p><b>왜 INSERT 를 먼저 하나.</b> 순서를 반대로(UPDATE → INSERT) 하면 아래 경합에서 높은 version 이 조용히 버려진다.
     *
     * <pre>
     *   쓰기 A (version 2)                  쓰기 B (version 1)
     *   UPDATE → 행이 없어 0건
     *                                       INSERT version 1, commit
     *   INSERT … WHERE NOT EXISTS → 0건
     *   → false 로 끝남. 예외가 없어 재시도도 없고, DB 에는 version 1 이 남는다.
     * </pre>
     *
     * INSERT 를 먼저 하면 경합이 생겨도 둘 중 하나로만 끝난다.
     *
     * <ul>
     *   <li>다른 쓰기가 이미 commit 했다: INSERT 는 0건이고, 이어지는 UPDATE 가 commit 된 행과 version 을 비교한다.
     *   <li>다른 쓰기가 아직 commit 전이다: INSERT 가 키 충돌({@code DuplicateKeyException})로 실패하고 transaction
     *       전체가 rollback 된다. 호출한 쪽이 transaction 밖에서 다시 부르면 위 경우가 된다. (PostgreSQL 은 실패한 transaction
     *       안에서 다음 SQL 을 실행할 수 없으므로, 이 메서드 안에서 잡아 재시도하지 않는다.)
     * </ul>
     *
     * <p>재시도하는 곳: 동기화는 {@code CouponSync} 가 한 번 더 부르고, 이벤트는 Kafka error handler 가 같은 메시지를 다시 처리한다.
     *
     * @return 반영했으면 {@code true}
     */
    @Transactional
    public boolean apply(CouponSnapshot snapshot) {
        MapSqlParameterSource params = params(snapshot);
        boolean applied =
                jdbc.update(INSERT_IF_MISSING, params) == 1
                        || jdbc.update(UPDATE_IF_NEWER, params) == 1;
        if (applied) {
            replaceProducts(snapshot);
        }
        return applied;
    }

    /** 상품에 지금 발급 기간인 쿠폰. 소진된 쿠폰도 포함하며, 종료가 빠른 순서다. */
    public List<ProductCouponResponse> findActiveByProductId(long productId, Instant now) {
        return jdbc.query(
                FIND_ACTIVE_BY_PRODUCT,
                Map.of("productId", productId, "now", utc(now)),
                (rs, rowNum) ->
                        new ProductCouponResponse(
                                rs.getString("coupon_id"),
                                rs.getString("name"),
                                rs.getLong("fixed_discount"),
                                instant(rs.getTimestamp("starts_at")),
                                instant(rs.getTimestamp("ends_at")),
                                instant(rs.getTimestamp("expires_at")),
                                rs.getBoolean("sold_out")));
    }

    /** 아직 발급이 끝나지 않은 쿠폰 id. 주기 대조가 Commerce 목록에서 빠진 쿠폰을 다시 읽을 때 쓴다. */
    public List<String> findOpenCouponIds(Instant now) {
        return jdbc.queryForList(
                "SELECT coupon_id FROM coupon_display WHERE ends_at > :now ORDER BY coupon_id",
                Map.of("now", utc(now)),
                String.class);
    }

    private void replaceProducts(CouponSnapshot snapshot) {
        jdbc.update(
                "DELETE FROM coupon_display_product WHERE coupon_id = :couponId",
                Map.of("couponId", snapshot.couponId()));
        MapSqlParameterSource[] rows =
                snapshot.productIds().stream()
                        .distinct()
                        .map(
                                productId ->
                                        new MapSqlParameterSource()
                                                .addValue("couponId", snapshot.couponId())
                                                .addValue("productId", productId))
                        .toArray(MapSqlParameterSource[]::new);
        if (rows.length > 0) {
            jdbc.batchUpdate(
                    "INSERT INTO coupon_display_product (coupon_id, product_id)"
                            + " VALUES (:couponId, :productId)",
                    rows);
        }
    }

    private static MapSqlParameterSource params(CouponSnapshot snapshot) {
        return new MapSqlParameterSource()
                .addValue("couponId", snapshot.couponId())
                .addValue("name", snapshot.name())
                .addValue("fixedDiscount", snapshot.fixedDiscount())
                .addValue("startsAt", utc(snapshot.startsAt()))
                .addValue("endsAt", utc(snapshot.endsAt()))
                .addValue("expiresAt", utc(snapshot.expiresAt()))
                .addValue("soldOut", snapshot.soldOut())
                .addValue("version", snapshot.version())
                .addValue("updatedAt", OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
