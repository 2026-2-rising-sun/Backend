package com.shoppinglive.commercecore.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commercecore.sales.controller.SalesController;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.exception.ConcurrentStateChangeException;
import com.shoppinglive.commercecore.sales.service.SalesLookupService;
import com.shoppinglive.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommerceCoreExceptionHandlerTest {
    @Test
    @DisplayName("낙관적 잠금과 상태 CAS 충돌은 모두 409로 응답한다")
    void mapsOptimisticAndConditionalStateConflictsTo409() throws Exception {
        com.shoppinglive.commercecore.sku.service.SkuSalesService service =
                mock(com.shoppinglive.commercecore.sku.service.SkuSalesService.class);
        when(service.changePrice(1L, 100L, "seller"))
                .thenThrow(new ObjectOptimisticLockingFailureException("Sales", 1L));
        when(service.changeStatus(1L, SalesStatus.ON_SALE, "seller"))
                .thenThrow(new ConcurrentStateChangeException(1L));
        SalesController controller = new SalesController(service, mock(SalesLookupService.class));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setCustomArgumentResolvers(
                                new org.springframework.web.method.support
                                        .HandlerMethodArgumentResolver() {
                                    public boolean supportsParameter(
                                            org.springframework.core.MethodParameter parameter) {
                                        return parameter.getParameterType()
                                                == com.shoppinglive.common.security
                                                        .AuthenticatedUser.class;
                                    }

                                    public Object resolveArgument(
                                            org.springframework.core.MethodParameter p,
                                            org.springframework.web.method.support
                                                            .ModelAndViewContainer
                                                    m,
                                            org.springframework.web.context.request.NativeWebRequest
                                                    w,
                                            org.springframework.web.bind.support
                                                            .WebDataBinderFactory
                                                    b) {
                                        return new com.shoppinglive.common.security
                                                .AuthenticatedUser(
                                                "seller",
                                                java.util.Set.of("SELLER"),
                                                java.time.Instant.now().plusSeconds(60));
                                    }
                                })
                        .setControllerAdvice(
                                new CommerceCoreExceptionHandler(), new GlobalExceptionHandler())
                        .build();
        mvc.perform(
                        patch("/v1/sales/1/price")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"price\":100}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/v1/sales/1/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"ON_SALE\"}"))
                .andExpect(status().isConflict());
    }
}
