package com.shoppinglive.commerce.coupon.service;

import com.shoppinglive.commerce.coupon.dto.CouponDisplaySnapshot;
import com.shoppinglive.commerce.coupon.dto.CouponSnapshotPage;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponSnapshotService {
    private final JdbcTemplate jdbc;

    public CouponSnapshotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CouponSnapshotPage list(String afterId, int size) {
        if (size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~100 범위여야 합니다.");
        }
        if (afterId != null) requireId(afterId);
        var found =
                jdbc.query(
                        "SELECT * FROM coupon_definition WHERE ends_at>? AND id>? ORDER BY id LIMIT ?",
                        this::map,
                        Timestamp.from(Instant.now()),
                        afterId == null ? "" : afterId,
                        size + 1);
        boolean hasNext = found.size() > size;
        var items = hasNext ? found.subList(0, size) : found;
        return new CouponSnapshotPage(items, hasNext ? items.getLast().couponId() : null, hasNext);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CouponDisplaySnapshot get(String id) {
        requireId(id);
        return jdbc.query("SELECT * FROM coupon_definition WHERE id=?", this::map, id).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 36) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "쿠폰 ID는 비어 있지 않은 36자 이하 문자열이어야 합니다.");
        }
    }

    private CouponDisplaySnapshot map(ResultSet row, int index) throws SQLException {
        String id = row.getString("id");
        return new CouponDisplaySnapshot(
                id,
                row.getLong("snapshot_version"),
                row.getString("name"),
                row.getLong("fixed_discount"),
                jdbc.queryForList(
                        "SELECT product_id FROM coupon_target WHERE coupon_id=? ORDER BY product_id",
                        Long.class,
                        id),
                row.getTimestamp("starts_at").toInstant(),
                row.getTimestamp("ends_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                row.getInt("issued_count") >= row.getInt("issuance_limit"));
    }
}
