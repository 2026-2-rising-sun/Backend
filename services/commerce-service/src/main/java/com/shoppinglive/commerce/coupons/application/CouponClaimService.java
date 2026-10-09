package com.shoppinglive.commerce.coupons.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponClaimService {
    public record MemberCoupon(String id,String couponId,String name,long fixedDiscount,String status,
        Instant claimedAt,Instant expiresAt) { }
    public record Claim(MemberCoupon coupon,boolean created) { }
    public record AvailableCoupon(String id,String name,long fixedDiscount,int remaining,Instant endsAt,Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CouponClaimService(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this(jdbc,manager,Clock.systemUTC());
    }
    public CouponClaimService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);this.clock=clock;
    }

    public Claim claim(String member,String couponId) {
        return transaction.execute(status -> {
            var definitions=jdbc.queryForList("SELECT * FROM coupon_definition WHERE id=? FOR UPDATE",couponId);
            if(definitions.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"쿠폰을 찾을 수 없습니다.");
            var existing=owned(member,couponId);
            if(!existing.isEmpty())return new Claim(existing.getFirst(),false);
            Instant now=Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            int changed=jdbc.update("""
                UPDATE coupon_definition SET issued_count=issued_count+1
                WHERE id=? AND starts_at<=? AND ends_at>? AND expires_at>?
                  AND issued_count<issuance_limit
                """,couponId,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
            if(changed!=1)throw new BusinessException(ErrorCode.CONFLICT,"발급 기간이 아니거나 수량이 소진되었습니다.");
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
                id,couponId,member,Timestamp.from(now));
            return new Claim(owned(member,couponId).getFirst(),true);
        });
    }

    public List<AvailableCoupon> available(String member,int page,int size) {
        page(page,size);Timestamp now=Timestamp.from(Instant.now(clock));
        return jdbc.query("""
            SELECT c.* FROM coupon_definition c WHERE starts_at<=? AND ends_at>? AND expires_at>?
              AND issued_count<issuance_limit AND NOT EXISTS
                (SELECT 1 FROM member_coupon m WHERE m.coupon_id=c.id AND m.member_id=?)
            ORDER BY ends_at,id LIMIT ? OFFSET ?
            """,(r,i)->new AvailableCoupon(r.getString("id"),r.getString("name"),r.getLong("fixed_discount"),
                r.getInt("issuance_limit")-r.getInt("issued_count"),r.getTimestamp("ends_at").toInstant(),
                r.getTimestamp("expires_at").toInstant()),now,now,now,member,size,(long)page*size);
    }

    public List<MemberCoupon> list(String member,int page,int size) {
        page(page,size);
        return jdbc.query(SELECT+" WHERE m.member_id=? ORDER BY m.claimed_at DESC,m.id LIMIT ? OFFSET ?",this::map,
            member,size,(long)page*size);
    }

    private List<MemberCoupon> owned(String member,String couponId) {
        return jdbc.query(SELECT+" WHERE m.member_id=? AND m.coupon_id=?",this::map,member,couponId);
    }

    private static final String SELECT="""
        SELECT m.id,m.coupon_id,m.status,m.claimed_at,c.name,c.fixed_discount,c.expires_at
        FROM member_coupon m JOIN coupon_definition c ON c.id=m.coupon_id
        """;
    private MemberCoupon map(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        Instant expiry=r.getTimestamp("expires_at").toInstant();String state=r.getString("status");
        if(state.equals("AVAILABLE") && !Instant.now(clock).isBefore(expiry))state="EXPIRED";
        return new MemberCoupon(r.getString("id"),r.getString("coupon_id"),r.getString("name"),
            r.getLong("fixed_discount"),state,r.getTimestamp("claimed_at").toInstant(),expiry);
    }
    private static void page(int page,int size) {
        if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"페이지 범위를 확인해 주세요.");
    }
}
