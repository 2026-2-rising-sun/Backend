package com.shoppinglive.live.like;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.like.api.LikeController;
import com.shoppinglive.live.like.api.LikeStorageExceptionHandler;
import com.shoppinglive.live.like.application.LikeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

class LikeStorageErrorTest {
    @Test void connectionFailureBeforeServiceEntryReturnsRetryable503() throws Exception {
        final LikeService likes = mock(LikeService.class);
        when(likes.total(1L)).thenThrow(new CannotCreateTransactionException("database unavailable"));
        MockMvcBuilders.standaloneSetup(new LikeController(likes))
            .setControllerAdvice(new LikeStorageExceptionHandler()).build()
            .perform(get("/v1/broadcasts/1/likes"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}
