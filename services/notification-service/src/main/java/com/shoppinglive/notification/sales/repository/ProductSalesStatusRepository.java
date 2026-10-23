package com.shoppinglive.notification.sales.repository;

import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.event.SalesInfoEventContract;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 판매 상태 사본({@code product_sales_status})을 읽고 쓴다. 상품 하나에 행 하나다.
 *
 * <p>저장된 값보다 큰 {@code snapshotVersion} 만 반영한다. 그러면 같은 이벤트가 두 번 와도 0건 갱신으로 끝나고, 늦게 도착한 옛 스냅샷이 새 상태를
 * 덮지 않는다.
 *
 * <p><b>transaction 안에서 {@link #apply} 를 부르면 안 된다.</b> 동시 최초 INSERT 의 키 충돌을 잡은 뒤 UPDATE 로 이어 가는데,
 * PostgreSQL 은 오류가 난 transaction 의 후속 문장을 모두 거부한다.
 */
@Repository
public class ProductSalesStatusRepository {

    // 이전 버전과 함께 실행할 수 있도록 기존 컬럼을 유지한다. 판별에는 raw_status만 사용한다.
    private static final String INSERT_IF_MISSING =
            """
            INSERT INTO product_sales_status
                (product_id, sales_id, snapshot_version, in_stock, raw_status, created_at, updated_at)
            SELECT :productId, :salesId, :snapshotVersion, :inStock, :status, :now, :now
            WHERE NOT EXISTS (
                SELECT 1 FROM product_sales_status WHERE product_id = :productId)
            """;
    private static final String UPDATE_IF_NEWER =
            """
            UPDATE product_sales_status
            SET sales_id = :salesId, snapshot_version = :snapshotVersion,
                in_stock = :inStock, raw_status = :status, updated_at = :now
            WHERE product_id = :productId AND snapshot_version < :snapshotVersion
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProductSalesStatusRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 없으면 INSERT, 있으면 버전이 더 클 때만 UPDATE 한다.
     *
     * @return 행이 바뀌었으면 {@code true}. 중복이나 역순 이벤트는 {@code false}
     */
    public boolean apply(ProductSalesStatus status) {
        int inserted;
        try {
            inserted = jdbc.update(INSERT_IF_MISSING, params(status));
        } catch (DuplicateKeyException e) {
            // NOT EXISTS 는 다른 쓰기의 미커밋 행을 보지 못한다. 같은 상품을 동시에 INSERT 하면
            // 한쪽은 PK 충돌이 난다. 먼저 들어간 행과 버전을 비교해야 하므로 UPDATE 로 넘어간다.
            inserted = 0;
        }
        return inserted == 1 || jdbc.update(UPDATE_IF_NEWER, params(status)) == 1;
    }

    public Optional<ProductSalesStatus> findByProductId(long productId) {
        return jdbc
                .query(
                        """
                        SELECT product_id, sales_id, snapshot_version, raw_status AS status
                        FROM product_sales_status WHERE product_id = :productId
                        """,
                        Map.of("productId", productId),
                        (rs, rowNum) ->
                                new ProductSalesStatus(
                                        rs.getLong("product_id"),
                                        rs.getLong("sales_id"),
                                        rs.getLong("snapshot_version"),
                                        rs.getString("status")))
                .stream()
                .findFirst();
    }

    private static Map<String, Object> params(ProductSalesStatus status) {
        return Map.of(
                "productId", status.productId(),
                "salesId", status.salesId(),
                "snapshotVersion", status.snapshotVersion(),
                "status", status.status(),
                "inStock", SalesInfoEventContract.ON_SALE.equals(status.status()),
                "now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }
}
