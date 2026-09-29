package com.shoppinglive.commerce.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.sales.api.SalesController;
import com.shoppinglive.commerce.sales.application.ConcurrentStateChangeException;
import com.shoppinglive.commerce.sales.application.SalesLookupService;
import com.shoppinglive.commerce.sales.application.SalesRegistrationService;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommerceExceptionHandlerTest {
    @Test
    void 낙관적락과_상태CAS_충돌은_모두_409로_응답한다() throws Exception {
        SalesService service = mock(SalesService.class);
        when(service.changePrice(1L, 100L))
            .thenThrow(new ObjectOptimisticLockingFailureException("Sales", 1L));
        when(service.changeStatus(1L, SalesStatus.ON_SALE))
            .thenThrow(new ConcurrentStateChangeException(1L));
        SalesController controller = new SalesController(
            service, mock(SalesRegistrationService.class), mock(SalesLookupService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommerceExceptionHandler(), new GlobalExceptionHandler()).build();
        mvc.perform(patch("/v1/sales/1/price").contentType(MediaType.APPLICATION_JSON)
            .content("{\"price\":100}")).andExpect(status().isConflict());
        mvc.perform(patch("/v1/sales/1/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ON_SALE\"}")).andExpect(status().isConflict());
    }
}
