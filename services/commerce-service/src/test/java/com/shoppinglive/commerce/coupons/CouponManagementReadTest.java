package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import com.shoppinglive.commerce.coupons.application.CouponCreationService;
import com.shoppinglive.commerce.coupons.application.CouponManagementService;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:coupon_management_read;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Sql(scripts="/db/migration/V5__coupon_definitions.sql",executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CouponManagementReadTest extends CommerceSecurityTestSupport {
    @Autowired CouponCreationService creation;
    @Autowired CouponManagementService management;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    @AfterEach
    void clean() { jdbc.update("DELETE FROM coupon_target"); jdbc.update("DELETE FROM coupon_definition"); shopping.clear(); }

    @Test
    void ownListAndDetailPreserveTargetsAndExcludeAnotherSeller() {
        Instant start=Instant.now().plusSeconds(3600),end=start.plusSeconds(3600);
        shopping.register(new ProductSnapshot(1L,"본인",null,SELLER));
        shopping.register(new ProductSnapshot(2L,"타인",null,MEMBER_A));
        var own=creation.create(SELLER,"본인 쿠폰",100,1,start,end,end,List.of(1L));
        var other=creation.create(MEMBER_A,"타인 쿠폰",100,1,start,end,end,List.of(2L));
        assertThat(management.list(SELLER,0,20).items()).containsExactly(own);
        assertThat(management.get(SELLER,own.id()).productIds()).containsExactly(1L);
        assertThatThrownBy(() -> management.get(SELLER,other.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> management.list(SELLER,Integer.MAX_VALUE,100)).isInstanceOf(BusinessException.class);
    }
}
