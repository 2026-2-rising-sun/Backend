package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import com.shoppinglive.commerce.coupons.application.CouponCreationService;
import com.shoppinglive.commerce.coupons.application.CouponManagementService;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:coupon_management_update;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Sql(scripts="/db/migration/V5__coupon_definitions.sql",executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CouponManagementUpdateTest extends CommerceSecurityTestSupport {
    @Autowired CouponCreationService creation;
    @Autowired CouponManagementService management;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    @AfterEach
    void clean() { jdbc.update("DELETE FROM coupon_target"); jdbc.update("DELETE FROM coupon_definition"); shopping.clear(); }

    @Test
    void versionConflictAndEventStartedDoNotChangeSettings() {
        Instant start=Instant.now().plusSeconds(3600),end=start.plusSeconds(3600);
        shopping.register(new ProductSnapshot(1L,"상품",null,SELLER));
        var coupon=creation.create(SELLER,"original",100,1,start,end,end,List.of(1L));
        var edited=management.update(SELLER,coupon.id(),0,"edited",200,2,start,end,end,List.of(1L));
        assertThat(edited.version()).isEqualTo(1);
        assertThatThrownBy(() -> management.update(SELLER,coupon.id(),0,"stale",300,3,start,end,end,List.of(1L)))
            .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE coupon_definition SET starts_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),coupon.id());
        assertThatThrownBy(() -> management.update(SELLER,coupon.id(),1,"late",300,3,start,end,end,List.of(1L)))
            .isInstanceOf(BusinessException.class);
        assertThat(management.get(SELLER,coupon.id()).name()).isEqualTo("edited");
        assertThat(management.get(SELLER,coupon.id()).fixedDiscount()).isEqualTo(200);
    }

    @Test
    void concurrentUpdatesWithSameVersionHaveExactlyOneWinner() throws Exception {
        Instant start=Instant.now().plusSeconds(3600),end=start.plusSeconds(3600);
        shopping.register(new ProductSnapshot(1L,"상품",null,SELLER));
        var coupon=creation.create(SELLER,"original",100,1,start,end,end,List.of(1L));
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of("first","second").stream().map(name -> executor.submit(() -> {
                ready.countDown();go.await();
                try { management.update(SELLER,coupon.id(),0,name,200,2,start,end,end,List.of(1L)); return true; }
                catch(BusinessException conflict) {
                    assertThat(conflict.errorCode()).isEqualTo(com.shoppinglive.common.core.ErrorCode.CONFLICT);
                    return false;
                }
            })).toList();
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(tasks.get(0).get()).isNotEqualTo(tasks.get(1).get());
        }
        assertThat(management.get(SELLER,coupon.id()).version()).isEqualTo(1);
    }
}
