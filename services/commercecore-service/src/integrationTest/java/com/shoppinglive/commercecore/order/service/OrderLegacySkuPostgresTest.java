package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.commercecore.order.dto.CreateOrderCommand;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "commercecore.refunds.api-enabled=true",
            "commercecore.refunds.execution-enabled=true",
            "commercecore.order.closure-recovery.initial-delay=PT1H"
        })
@AutoConfigureMockMvc
class OrderLegacySkuPostgresTest extends OrderSheetPostgresSupport {
    @Autowired OrderCreationService legacyCreation;
    @Autowired OrderCheckoutService quote;
    @Autowired InMemoryProductLookupStub lookup;

    @BeforeEach
    void seedPreservedDefaultIdentity() {
        lookup.register(new ProductSnapshot(101L, "상품101", null, SELLER, true));
        jdbc.update("DELETE FROM sku WHERE id=503");
        // 구 테이블 거래 값이 달라도 신규 가격·수량은 SKU 원본만 따른다.
        jdbc.update(
                "INSERT INTO sales_info(id,product_id,price,status,created_at,updated_at) VALUES(501,101,99000,'PRIVATE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update(
                "INSERT INTO sales_stock(sales_info_id,available,reserved,created_at,updated_at) VALUES(501,99,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    Order legacy(String key) {
        return legacyCreation
                .create(new CreateOrderCommand(101L, 2, "회원", "01012345678", MEMBER_A, 8000L), key)
                .order();
    }

    @Test
    @DisplayName("단일SKU 구API는 현재SKU 가격·재고와 신규600초 기한을 사용한다")
    void legacyCreationReadsSingleSkuSource() {
        assertThat(quote.preview(101L, 2).unitPrice()).isEqualTo(4000);
        var order = legacy("legacy");
        assertThat(order.getTradeSkuId()).isEqualTo(501);
        assertThat(order.getSalesInfoId()).isNull();
        assertThat(order.getUnitPrice()).isEqualTo(4000);
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT policy_version FROM payment_group", Integer.class))
                .isEqualTo(0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM expires_at-created_at)::BIGINT FROM payment_group",
                                Long.class))
                .isBetween(599L, 600L);
        assertThatThrownBy(() -> create(1)).isInstanceOf(BusinessException.class);
        assertThat(legacy("legacy").getId()).isEqualTo(order.getId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM sales_stock WHERE sales_info_id=501",
                                Integer.class))
                .isEqualTo(99);
    }

    @Test
    @DisplayName("Sales 이력 없는 신규 SKU도 구 API 생성·재요청·취소와 재고 복구가 가능하다")
    void legacyApiAcceptsSkuWithoutSalesHistory() {
        jdbc.update("DELETE FROM sales_stock WHERE sales_info_id=501");
        jdbc.update("DELETE FROM sales_info WHERE id=501");
        var order = legacy("sku-only");
        assertThat(order.getSalesInfoId()).isNull();
        assertThat(order.getSkuId()).isEqualTo(501);
        assertThat(order.getProductIdSnapshot()).isEqualTo(101);
        assertThat(legacy("sku-only").getId()).isEqualTo(order.getId());
        legacyGroups.cancel(MEMBER_A, order.getPaymentGroup().getGroupNumber());
        legacyGroups.cancel(MEMBER_A, order.getPaymentGroup().getGroupNumber());
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("구 productId 주문은 SKU가 여러 개면 임의 옵션을 선택하지 않는다")
    void legacyApiRejectsAmbiguousSku() {
        jdbc.update("UPDATE sku SET product_id=101 WHERE id=502");
        assertThatThrownBy(() -> legacy("ambiguous"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(com.shoppinglive.common.core.ErrorCode.CONFLICT);
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("신규 구 API 주문의 취소와 만료는 SKU 예약을 한 번만 해제한다")
    void legacyCancellationAndExpiryRemainCompatible() {
        var first = legacy("cancel");
        legacyGroups.cancel(MEMBER_A, first.getPaymentGroup().getGroupNumber());
        assertThat(available(501)).isEqualTo(20);
        legacyGroups.cancel(MEMBER_A, first.getPaymentGroup().getGroupNumber());
        assertThat(available(501)).isEqualTo(20);
        var next = legacy("expire");
        jdbc.update(
                "UPDATE payment_group SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                next.getPaymentGroup().getId());
        assertThat(legacyGroups.expire(next.getPaymentGroup().getId())).isTrue();
        assertThat(legacyGroups.expire(next.getPaymentGroup().getId())).isFalse();
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("옵션 Cart SKU를 기본 SKU로 바꿔 주문하지 않고 신규 주문서 경로에서 정확히 선점한다")
    void optionSkuNeverBecomesDefaultSku() {
        seedSku(503, 101, 5000, 20);
        jdbc.update(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES (?,101,503,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                MEMBER_A);
        long id = jdbc.queryForObject("SELECT id FROM cart_item", Long.class);
        assertThatThrownBy(
                        () ->
                                legacyGroups.create(
                                        MEMBER_A,
                                        List.of(new PaymentGroupService.Selection(id, 0L)),
                                        "회원",
                                        "01012345678",
                                        5000L,
                                        "legacy-option"))
                .isInstanceOf(BusinessException.class);
        assertThat(available(501)).isEqualTo(20);
        var order =
                sheets.create(
                                MEMBER_A,
                                "option",
                                new com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest(
                                        null,
                                        List.of(
                                                new com.shoppinglive.commercecore.cart.dto
                                                        .CartSelection(id, 0L)),
                                        "회원",
                                        "01012345678"))
                        .order();
        assertThat(order.items().getFirst().skuId()).isEqualTo(503);
        assertThat(available(503)).isEqualTo(19);
        assertThat(available(501)).isEqualTo(20);
    }

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    @DisplayName("옛 구 API 주문은 결제할 수 없고 환불은 409이며 만료 뒤에는 새 구매를 막지 않는다")
    void legacyOrderCannotBePaidRefundedOrBlockNewPurchase() throws Exception {
        var order = legacy("legacy-closed");
        String number = order.getPaymentGroup().getGroupNumber();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/v1/payment-groups/" + number + "/payments")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "pay-legacy"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/v1/payment-groups/" + number + "/refunds")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "refund-legacy"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/v1/payment-groups/" + number + "/refundable-targets")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data[0].unavailableReason")
                                .value("LEGACY_REFUND_UNAVAILABLE"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/v1/payment-groups/" + number)
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
        jdbc.update(
                "UPDATE payment_group SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                order.getPaymentGroup().getId());
        assertThat(legacyGroups.expire(order.getPaymentGroup().getId())).isTrue();
        assertThat(legacyGroups.active(MEMBER_A)).isEmpty();
        assertThat(legacy("after-expiry").getPaymentGroup()).isNotNull();
    }
}
