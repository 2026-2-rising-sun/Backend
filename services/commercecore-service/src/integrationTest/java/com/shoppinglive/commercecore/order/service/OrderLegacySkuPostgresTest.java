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
    @DisplayName("기존 단일 SKU API는 같은 Sales ID를 유지하며 SKU 현재 가격과 재고를 사용한다")
    void legacyCreationReadsSingleSkuSource() {
        assertThat(quote.preview(101L, 2).unitPrice()).isEqualTo(4000);
        var order = legacy("legacy");
        assertThat(order.getSalesInfoId()).isEqualTo(501);
        assertThat(order.getUnitPrice()).isEqualTo(4000);
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT policy_version FROM payment_group", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM expires_at-created_at)::BIGINT FROM payment_group",
                                Long.class))
                .isBetween(899L, 900L);
        assertThatThrownBy(() -> create(1)).isInstanceOf(BusinessException.class);
        assertThat(legacy("legacy").getId()).isEqualTo(order.getId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM sales_stock WHERE sales_info_id=501",
                                Integer.class))
                .isEqualTo(99);
    }

    @Test
    @DisplayName("기존 주문 결제 성공은 판매 중지 뒤에도 같은 SKU 예약만 한 번 소진한다")
    void legacySuccessDoesNotRevalidateSale() {
        var order = legacy("paid");
        jdbc.update("UPDATE product SET sale_enabled=FALSE WHERE id=101");
        jdbc.update("UPDATE sku SET sale_status='PRIVATE' WHERE id=501");
        var payment =
                legacyGroups.start(MEMBER_A, order.getPaymentGroup().getGroupNumber(), "payment");
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        dispatcher.payment(payment.paymentId());
        dispatcher.payment(payment.paymentId());
        assertThat(available(501)).isEqualTo(18);
        assertThat(reserved(501)).isZero();
        assertThat(
                        legacyGroups
                                .get(MEMBER_A, order.getPaymentGroup().getGroupNumber())
                                .status()
                                .name())
                .isEqualTo("PAID");
    }

    @Test
    @DisplayName("기존 취소와 만료는 원래 15분 기한·상태를 유지하며 SKU 예약만 한 번 해제한다")
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
}
