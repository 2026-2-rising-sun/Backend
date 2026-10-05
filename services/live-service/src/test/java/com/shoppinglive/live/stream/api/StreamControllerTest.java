package com.shoppinglive.live.stream.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.common.web.GlobalExceptionHandler;
import com.shoppinglive.live.stream.application.BroadcastStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StreamControllerTest {
    @Test
    void databaseFailureBeforeStreamingReturnsJson() throws Exception {
        final BroadcastStreamService streams = mock(BroadcastStreamService.class);
        when(streams.open(1L)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StreamController(streams))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/v1/broadcasts/1/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }
}
