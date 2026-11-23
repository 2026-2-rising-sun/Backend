package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.order.dto.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(properties = {"commercecore.product.lookup.mode=local"})
@AutoConfigureMockMvc
class OrderSheetDomainPostgresTest extends OrderSheetPostgresSupport {
    @MockitoSpyBean OrderTimeSource time;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @BeforeEach
    void freezeTime() {
        doReturn(
                        jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class)
                                .toInstant())
                .when(time)
                .now();
    }

    @Test
    @DisplayName("내부 주문 도메인은 다중항목 스냅샷과600초 선점을 원자적으로 보존하고 동일키를 재현한다")
    void snapshotsAndReplaysWithoutFinancialExecution() {
        var items =
                List.of(
                        new CreateOrderSheetRequest.Item(501L, 2),
                        new CreateOrderSheetRequest.Item(502L, 1));
        var first = create("same", items);
        assertThat(first.items()).hasSize(2);
        assertThat(first.totalAmount()).isEqualTo(11000);
        assertThat(first.expiresAt()).isEqualTo(time.now().plusSeconds(600));
        jdbc.update("UPDATE sku SET price=9999 WHERE id=501");
        var replay = create("same", items);
        assertThat(replay.orderNumber()).isEqualTo(first.orderNumber());
        assertThat(replay.items().getFirst().unitPrice()).isEqualTo(4000);
        assertThat(replay.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(reserved(501)).isEqualTo(2);
        lifecycle.cancel(MEMBER_A, first.orderNumber());
        lifecycle.cancel(MEMBER_A, first.orderNumber());
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        assertNoFinancialExecution();
    }

    @Test
    @DisplayName("내부 주문은 정확600초에 로컬 만료하고 재고를 한 번만 반환한다")
    void expiresLocallyAtBoundaryOnce() {
        var order = create(2);
        doReturn(order.expiresAt().minusNanos(1000)).when(time).now();
        assertThat(lifecycle.expire(groupId(order))).isFalse();
        doReturn(order.expiresAt()).when(time).now();
        assertThat(lifecycle.expire(groupId(order))).isTrue();
        assertThat(lifecycle.expire(groupId(order))).isFalse();
        assertThat(available(501)).isEqualTo(20);
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("EXPIRED");
        assertNoFinancialExecution();
    }

    @Test
    @DisplayName("한 항목 선점실패는 다중항목 주문과 모든 재고 변경을 롤백한다")
    void rollsBackWholeReservation() {
        assertThatThrownBy(
                        () ->
                                create(
                                        "bad",
                                        List.of(
                                                new CreateOrderSheetRequest.Item(501L, 1),
                                                new CreateOrderSheetRequest.Item(502L, 21))))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Integer.class))
                .isZero();
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("새 주문서 핸들러가 있어도 미연결 내부 주문을 새 중계나 기존 결제 API로 우회 실행할 수 없다")
    void relayEntryDoesNotAllowUnboundOrLegacyFinancialBypass() throws Exception {
        assertThat(
                        mappings.getHandlerMethods().keySet().stream()
                                .flatMap(i -> i.getPatternValues().stream())
                                .filter(p -> p.startsWith("/v1/order-sheets")))
                .contains("/v1/order-sheets", "/v1/order-sheets/{number}/payment/approval");
        var order = create(1);
        mvc.perform(
                        post("/v1/order-sheets/" + order.orderNumber() + "/payment")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        post("/v1/payment-groups/" + order.orderNumber() + "/payments")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "bypass"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        get("/v1/payment-groups/" + order.orderNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        String itemNumber =
                jdbc.queryForObject(
                        "SELECT order_number FROM orders WHERE id=?",
                        String.class,
                        order.items().getFirst().orderItemId());
        mvc.perform(
                        post("/v1/orders/" + itemNumber + "/payments")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        post("/v1/payment-groups/" + order.orderNumber() + "/refunds")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "bypass-refund"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        assertNoFinancialExecution();
    }

    @Test
    @DisplayName("내부Cart선택은100개를 수락하고101개는 원자적 거절한다")
    void validatesCartSelectionBoundary() {
        var invalid = new ArrayList<CartSelection>();
        for (long i = 1; i <= 101; i++) invalid.add(new CartSelection(i, 0L));
        assertThatThrownBy(
                        () ->
                                sheets.create(
                                        MEMBER_A,
                                        "101",
                                        new CreateOrderSheetRequest(
                                                null, invalid, "구매자", "01012345678")))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Integer.class))
                .isZero();
        var selected = new ArrayList<CartSelection>();
        for (long i = 0; i < 100; i++) {
            long product = 201 + i, sku = 601 + i;
            jdbc.update(
                    "INSERT INTO product(id,name,description,main_image_id,seller_id,sale_enabled,version,created_at,updated_at) VALUES (?,?,'설명',1,?,TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    product,
                    "상품" + product,
                    SELLER);
            seedSku(sku, product, 1, 1);
            long cart =
                    jdbc.queryForObject(
                            "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(?,?,?,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                            Long.class,
                            MEMBER_A,
                            product,
                            sku);
            selected.add(new CartSelection(cart, 0L));
        }
        assertThat(
                        sheets.create(
                                        MEMBER_A,
                                        "100",
                                        new CreateOrderSheetRequest(
                                                null, selected, "구매자", "01012345678"))
                                .order()
                                .items())
                .hasSize(100);
    }

    private void assertNoFinancialExecution() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_delivery_request", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass('order_payment_closure')", String.class))
                .isNull();
    }
}
