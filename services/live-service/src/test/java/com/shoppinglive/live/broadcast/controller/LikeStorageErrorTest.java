package com.shoppinglive.live.broadcast.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.service.LikeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

class LikeStorageErrorTest {
    @Test
    @DisplayName("서비스 실행 전 연결 실패는 재시도 가능한 503 응답으로 반환한다")
    void connectionFailureBeforeServiceEntryReturnsRetryable503() throws Exception {
        final LikeService likes = mock(LikeService.class);
        when(likes.total(1L)).thenThrow(new CannotCreateTransactionException("DB를 사용할 수 없습니다."));
        MockMvcBuilders.standaloneSetup(new LikeController(likes))
                .setControllerAdvice(new LikeStorageExceptionHandler())
                .build()
                .perform(get("/v1/broadcasts/1/likes"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}
