package com.shoppinglive.commercecore.cart.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commercecore.cart.dto.CartProductGroup;
import com.shoppinglive.commercecore.cart.dto.CartSkuView;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.service.CartService;
import com.shoppinglive.commercecore.order.service.OrderCreationService;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.common.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    @Mock CartService cart;
    @Mock OrderCreationService orders;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal =
                new AuthenticatedUser(MEMBER, Set.of("USER"), Instant.now().plusSeconds(60));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        mvc =
                MockMvcBuilders.standaloneSetup(new CartController(cart, orders))
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("장바구니 조회는 상품별 묶음 안에 SKU별 수량과 현재가를 반환한다")
    void returnsProductGroups() throws Exception {
        when(cart.listGrouped(MEMBER))
                .thenReturn(
                        List.of(
                                new CartProductGroup(
                                        501L,
                                        "상품",
                                        null,
                                        true,
                                        List.of(
                                                new CartSkuView(
                                                        7L, 101L, 2, 0L, 1000L, 10, "ON_SALE",
                                                        true),
                                                new CartSkuView(
                                                        8L, 102L, 5, 0L, 2000L, 10, "ON_SALE",
                                                        true)))));

        mvc.perform(get("/v1/cart/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(501))
                .andExpect(jsonPath("$[0].items[0].skuId").value(101))
                .andExpect(jsonPath("$[0].items[1].quantity").value(5))
                .andExpect(jsonPath("$[0].items[1].unitPrice").value(2000));
    }

    @Test
    @DisplayName("SKU 식별자로 담으면 인증된 회원의 SKU 항목과 상품 참조를 반환한다")
    void addsSkuReference() throws Exception {
        when(cart.addSku(MEMBER, 101L, 2)).thenReturn(item(2, 0L));

        mvc.perform(
                        post("/v1/cart/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"skuId\":101,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.skuId").value(101))
                .andExpect(jsonPath("$.productId").value(501));
        verify(cart).addSku(MEMBER, 101L, 2);
    }

    @Test
    @DisplayName("기존 상품 식별자 요청은 별도 호환 경로로 전달한다")
    void routesLegacyProductReference() throws Exception {
        when(cart.add(MEMBER, 501L, 2)).thenReturn(item(2, 0L));

        mvc.perform(
                        post("/v1/cart/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":501,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skuId").value(101));
        verify(cart).add(MEMBER, 501L, 2);
    }

    @Test
    @DisplayName("SKU와 상품 식별자를 함께 보내거나 둘 다 생략하면 거절한다")
    void rejectsAmbiguousReference() throws Exception {
        for (String body :
                List.of("{\"skuId\":101,\"productId\":501,\"quantity\":2}", "{\"quantity\":2}")) {
            mvc.perform(
                            post("/v1/cart/items")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cart);
    }

    @Test
    @DisplayName("수량은 양수여야 하며 변경 요청에는 최종 수량만 필요하다")
    void validatesAndUpdatesAbsoluteQuantity() throws Exception {
        mvc.perform(
                        patch("/v1/cart/items/7")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        when(cart.changeQuantity(MEMBER, 7L, 4)).thenReturn(item(4, 1L));

        mvc.perform(
                        patch("/v1/cart/items/7")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(4))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("본인 항목 삭제는 응답 본문 없이 204를 반환한다")
    void deletesOwnedItem() throws Exception {
        mvc.perform(delete("/v1/cart/items/7")).andExpect(status().isNoContent());
        verify(cart).delete(MEMBER, 7L);
    }

    private CartItem item(int quantity, Long version) {
        CartItem item = new CartItem(MEMBER, 501L, 101L, quantity);
        ReflectionTestUtils.setField(item, "id", 7L);
        ReflectionTestUtils.setField(item, "version", version);
        return item;
    }
}
