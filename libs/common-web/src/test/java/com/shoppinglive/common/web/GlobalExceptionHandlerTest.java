package com.shoppinglive.common.web;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("공통 HTTP 오류 계약")
class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RequestController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @DisplayName("깨진 JSON과 잘못된 필드 타입은 400 INVALID_REQUEST다")
    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"scheduledAt\":\"not-a-date\"}",
            "{\"quantity\":\"not-a-number\"}", ""})
    void malformedRequestReturnsBadRequest(String body) throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("필수 헤더 누락은 400이다")
    @Test
    void missingHeaderReturnsBadRequest() throws Exception {
        mvc.perform(get("/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("필수 쿼리 파라미터 누락은 400이다")
    @Test
    void missingParameterReturnsBadRequest() throws Exception {
        mvc.perform(get("/quantity"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("쿼리 파라미터 타입 불일치는 400이다")
    @Test
    void invalidParameterReturnsBadRequest() throws Exception {
        mvc.perform(get("/quantity").param("quantity", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("도메인 오류의 상태와 코드는 보존된다")
    @Test
    void businessErrorKeepsItsContract() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @DisplayName("예상하지 못한 서버 오류는 계속 500이다")
    @Test
    void unexpectedErrorRemainsServerError() throws Exception {
        mvc.perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class RequestController {
        @PostMapping("/body")
        Body body(@RequestBody Body input) { return input; }

        @GetMapping("/header")
        String header(@RequestHeader("Idempotency-Key") String key) { return key; }

        @GetMapping("/quantity")
        int quantity(@RequestParam("quantity") int quantity) { return quantity; }

        @GetMapping("/missing")
        void missing() { throw new BusinessException(ErrorCode.NOT_FOUND); }

        @GetMapping("/failure")
        void failure() { throw new IllegalStateException("unexpected"); }
    }

    record Body(Instant scheduledAt, int quantity) { }
}
