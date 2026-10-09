package com.shoppinglive.commerce.coupons.application;

import com.shoppinglive.commerce.coupons.domain.CouponDefinition;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponManagementService {
    public record Page(List<CouponDefinition> items, int page, int size, long totalElements) { }
    private final JdbcTemplate jdbc;
    private final ShoppingClient shopping;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public CouponManagementService(JdbcTemplate jdbc, ShoppingClient shopping, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.shopping=shopping; this.transaction=new TransactionTemplate(manager);
        this.readTransaction=new TransactionTemplate(manager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Page list(String seller, int page, int size) {
        if (page<0 || size<1 || size>100 || (long) page*size>Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"페이지 범위를 확인해 주세요.");
        }
        return readTransaction.execute(status -> {
            List<CouponDefinition> items=jdbc.query("SELECT * FROM coupon_definition WHERE seller_id=? ORDER BY created_at DESC,id ASC LIMIT ? OFFSET ?",
                this::map,seller,size,(long)page*size);
            return new Page(items,page,size,jdbc.queryForObject("SELECT COUNT(*) FROM coupon_definition WHERE seller_id=?",Long.class,seller));
        });
    }

    public CouponDefinition get(String seller, String id) {
        return readTransaction.execute(status -> owned(seller,id,false));
    }

    public CouponDefinition update(String seller, String id, long expectedVersion, String name, long discount,
            int limit, Instant start, Instant end, Instant expiration, List<Long> products) {
        CouponDefinition.validate(name,discount,limit,start,end,expiration,products);
        Instant storedStart=start.truncatedTo(ChronoUnit.MICROS),storedEnd=end.truncatedTo(ChronoUnit.MICROS),
            storedExpiration=expiration.truncatedTo(ChronoUnit.MICROS);
        CouponDefinition.validate(name,discount,limit,storedStart,storedEnd,storedExpiration,products);
        for(long productId:products) {
            var product=shopping.findProduct(productId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,"대상 상품을 찾을 수 없습니다."));
            if(!seller.equals(product.sellerId()))throw new BusinessException(ErrorCode.FORBIDDEN,"본인 상품만 지정할 수 있습니다.");
        }
        return transaction.execute(status -> {
            CouponDefinition current=owned(seller,id,true);
            if(!Instant.now().isBefore(current.startsAt()))throw new BusinessException(ErrorCode.CONFLICT,"발급 시작 이후 설정을 변경할 수 없습니다.");
            if(current.version()!=expectedVersion)throw new BusinessException(ErrorCode.CONFLICT,"쿠폰 설정이 먼저 변경되었습니다.");
            if(limit<current.issuedCount())throw new BusinessException(ErrorCode.CONFLICT,"이미 발급한 수량보다 줄일 수 없습니다.");
            jdbc.update("UPDATE coupon_definition SET name=?,fixed_discount=?,issuance_limit=?,starts_at=?,ends_at=?,expires_at=?,version=version+1 WHERE id=?",
                name,discount,limit,Timestamp.from(storedStart),Timestamp.from(storedEnd),Timestamp.from(storedExpiration),id);
            jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?",id);
            for(long productId:products)jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)",id,productId);
            return owned(seller,id,false);
        });
    }

    private CouponDefinition owned(String seller,String id,boolean lock) {
        List<CouponDefinition> found=jdbc.query("SELECT * FROM coupon_definition WHERE id=?"+(lock?" FOR UPDATE":""),this::map,id);
        if(found.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"쿠폰을 찾을 수 없습니다.");
        CouponDefinition coupon=found.getFirst();
        if(!seller.equals(coupon.sellerId()))throw new BusinessException(ErrorCode.FORBIDDEN,"본인 쿠폰만 관리할 수 있습니다.");
        return coupon;
    }

    private CouponDefinition map(ResultSet row,int index) throws SQLException {
        String id=row.getString("id");
        return new CouponDefinition(id,row.getString("seller_id"),row.getString("name"),row.getLong("fixed_discount"),
            row.getInt("issuance_limit"),row.getInt("issued_count"),row.getTimestamp("starts_at").toInstant(),
            row.getTimestamp("ends_at").toInstant(),row.getTimestamp("expires_at").toInstant(),row.getLong("version"),
            jdbc.queryForList("SELECT product_id FROM coupon_target WHERE coupon_id=? ORDER BY product_id",Long.class,id));
    }
}
