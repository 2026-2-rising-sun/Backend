package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.payment.service.RefundIntakeService;
import com.shoppinglive.commercecore.product.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "commercecore.product.lookup.mode=local",
            "commercecore.refunds.api-enabled=true",
            "commercecore.refunds.execution-enabled=true"
        })
@AutoConfigureMockMvc
class LegacyOrderSellerSnapshotPostgresTest extends OrderSheetPostgresSupport {
    @Autowired OrderCreationService creation;
    @Autowired RefundIntakeService intake;
    @Autowired ProductLifecycleService products;
    @Autowired ProductLookup lookup;
    @Autowired ProductQueryService productQuery;

    @BeforeEach
    void singleSku() {
        jdbc.update("DELETE FROM sku WHERE id=503");
        assertThat(lookup).isSameAs(productQuery);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("상품 중지·삭제는 신규 주문만 차단하고 기존 선점 주문의 기한과 선점을 보존한다")
    void reservedOrdersKeepDeadlineAndReservationAfterProductClosure(boolean deleted) {
        var original =
                creation.create(
                                new CreateOrderCommand(
                                        101L, 1, "구매자", "01012345678", MEMBER_A, 4000L),
                                "reserved")
                        .order();
        var expires = original.getExpiresAt();
        if (deleted) products.delete(101L, version(), SELLER);
        else products.changeSaleState(101L, false, version(), SELLER);
        assertThatThrownBy(
                        () ->
                                creation.create(
                                        new CreateOrderCommand(
                                                101L, 1, "구매자", "01012345678", MEMBER_B, 4000L),
                                        "new"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT expires_at FROM orders", java.sql.Timestamp.class)
                                .toInstant())
                .isEqualTo(expires);
        assertThat(available(501)).isEqualTo(19);
        assertThat(reserved(501)).isEqualTo(1);
    }

    @Autowired com.shoppinglive.commercecore.sku.service.SkuSalesService stockChanges;

    private long version() {
        return jdbc.queryForObject("SELECT version FROM product WHERE id=101", Long.class);
    }
}
