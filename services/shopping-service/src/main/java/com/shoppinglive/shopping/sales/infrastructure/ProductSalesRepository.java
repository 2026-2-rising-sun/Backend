package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Commerce 판매정보의 조회용 사본({@code product_sales})을 읽고 쓴다. 상품 하나에 행 하나다.
 *
 * <p>쓰는 경로는 둘이다. 이벤트는 {@link #apply}, 초기 적재(backfill)는 {@link #insertIfAbsent} 를 쓴다.
 * 이벤트는 중복되거나 순서가 바뀌어 올 수 있어, 저장된 값보다 큰 {@code snapshotVersion} 만 반영해야
 * 옛 이벤트가 새 값을 덮지 않는다. 이벤트의 version 은 1 이상이므로 backfill 이 넣은 version 0 행은 첫 이벤트에 덮인다.
 *
 * <p>transaction 안에서 호출하면 안 된다. 동시 최초 INSERT 의 키 충돌을 잡은 뒤 UPDATE 로 이어 가는데,
 * PostgreSQL 은 오류가 난 transaction 의 후속 문장을 모두 거부한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductSalesRepository {

    private static final String INSERT_IF_MISSING = """
        INSERT INTO product_sales (product_id, sales_id, price, status, available, version, updated_at)
        SELECT :productId, :salesId, :price, :status, :available, :version, :updatedAt
        WHERE NOT EXISTS (SELECT 1 FROM product_sales WHERE product_id = :productId)
        """;
    private static final String UPDATE_IF_NEWER = """
        UPDATE product_sales
        SET sales_id = :salesId, price = :price, status = :status, available = :available,
            version = :version, updated_at = :updatedAt
        WHERE product_id = :productId AND version < :version
        """;
    private static final long BACKFILL_VERSION = 0L;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 이벤트 경로. 없으면 INSERT, 있으면 version 이 더 클 때만 UPDATE 한다.
     * 중복(같은 version)·역순(더 작은 version) 이벤트는 0건 갱신으로 끝나며 예외를 던지지 않는다.
     *
     * @return 행이 바뀌었으면 {@code true}
     */
    public boolean apply(SalesInfo info, long version) {
        int inserted;
        try {
            inserted = insertIfMissing(info, version);
        } catch (DuplicateKeyException e) {
            // NOT EXISTS 는 다른 쓰기의 미커밋 행을 보지 못한다. 같은 신규 상품을 동시에 INSERT 하면 한쪽은
            // PK 충돌이 난다. 먼저 들어간 행과 version 을 비교해야 하므로 UPDATE 로 넘어간다.
            inserted = 0;
        }
        return inserted == 1 || updateIfNewer(info, version) == 1;
    }

    /**
     * backfill 경로. version 0 으로 없는 행만 INSERT 하고, 이미 있으면 건드리지 않는다.
     * 적재 원본(Commerce HTTP 조회)에는 version 이 없어 이벤트 값과 어느 쪽이 새로운지 비교할 수 없다.
     * 그래서 이미 있는 행은 이벤트가 넣은 더 새로운 값으로 보고 남긴다.
     *
     * @return 새로 저장했으면 {@code true}
     */
    public boolean insertIfAbsent(SalesInfo info) {
        try {
            return insertIfMissing(info, BACKFILL_VERSION) == 1;
        } catch (DuplicateKeyException e) {
            // 다른 쓰기가 동시에 먼저 넣었다. 이미 있는 행과 같게 취급한다.
            return false;
        }
    }

    /** 상품 id 목록에 해당하는 판매정보만 돌려준다. 저장되지 않은 상품은 결과에서 빠진다. */
    public Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds) {
        Set<Long> ids = ProductIds.distinct(productIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<SalesInfo> rows = jdbc.query(
            "SELECT product_id, sales_id, price, status, available FROM product_sales "
                + "WHERE product_id IN (:productIds)",
            Map.of("productIds", ids),
            (rs, rowNum) -> new SalesInfo(rs.getLong("product_id"), rs.getLong("sales_id"), rs.getLong("price"),
                SalesStatus.valueOf(rs.getString("status")), rs.getInt("available")));
        return rows.stream().collect(Collectors.toMap(SalesInfo::productId, Function.identity()));
    }

    private int insertIfMissing(SalesInfo info, long version) {
        return jdbc.update(INSERT_IF_MISSING, params(info, version));
    }

    private int updateIfNewer(SalesInfo info, long version) {
        return jdbc.update(UPDATE_IF_NEWER, params(info, version));
    }

    private static Map<String, Object> params(SalesInfo info, long version) {
        return Map.of(
            "productId", info.productId(),
            "salesId", info.salesId(),
            "price", info.price(),
            "status", info.status().name(),
            "available", info.available(),
            "version", version,
            "updatedAt", OffsetDateTime.now(ZoneOffset.UTC));
    }
}
