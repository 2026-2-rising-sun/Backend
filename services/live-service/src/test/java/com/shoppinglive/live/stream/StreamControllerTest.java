package com.shoppinglive.live.stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StreamControllerTest {
    @Test
    @DisplayName("스트림 시작 전 DB 오류는 JSON 응답으로 반환한다")
    void databaseFailureBeforeStreamingReturnsJson() throws Exception {
        final BroadcastStreamService streams = mock(BroadcastStreamService.class);
        when(streams.open(1L)).thenThrow(new DataAccessResourceFailureException("DB를 사용할 수 없습니다."));
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new StreamController(streams))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();

        mvc.perform(get("/v1/broadcasts/1/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }
}
