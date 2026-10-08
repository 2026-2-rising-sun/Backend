package com.shoppinglive.commerce.coupons.application;

import com.shoppinglive.commerce.coupons.domain.CouponDefinition;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponCreationService {
    private final JdbcTemplate jdbc;
    private final ShoppingClient shopping;
    private final TransactionTemplate transaction;

    public CouponCreationService(JdbcTemplate jdbc, ShoppingClient shopping, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.shopping = shopping;
        this.transaction = new TransactionTemplate(manager);
    }

    public CouponDefinition create(String sellerId, String name, long discount, int limit,
            Instant start, Instant end, Instant expiration, List<Long> products) {
        CouponDefinition.validate(name, discount, limit, start, end, expiration, products);
        for (long productId : products) {
            var product = shopping.findProduct(productId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "대상 상품을 찾을 수 없습니다."));
            if (!sellerId.equals(product.sellerId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "본인 소유 상품만 쿠폰 대상으로 지정할 수 있습니다.");
            }
        }
        String id = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            jdbc.update("""
                INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,
                    starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, id, sellerId, name, discount, limit, Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(expiration), Timestamp.from(Instant.now()));
            for (long productId : products) {
                jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", id, productId);
            }
            return new CouponDefinition(id, sellerId, name, discount, limit, 0, start, end, expiration, 0, products);
        });
    }
}
