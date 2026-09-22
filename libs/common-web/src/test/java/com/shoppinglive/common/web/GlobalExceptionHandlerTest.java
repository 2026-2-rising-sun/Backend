package com.shoppinglive.common.web;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @DisplayName("서버의 경로 매핑 오류는 400으로 바뀌지 않는다")
    @Test
    void missingPathVariableRemainsServerError() throws Exception {
        mvc.perform(get("/broken-mapping"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @DisplayName("지원하지 않는 HTTP 메서드는 405와 Allow 헤더를 유지한다")
    @Test
    void unsupportedMethodKeepsStatusAndAllowedMethods() throws Exception {
        mvc.perform(post("/header"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("지원하지 않는 요청 본문 형식은 415를 유지한다")
    @Test
    void unsupportedMediaTypeKeepsStatus() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("없는 정적 리소스는 404 NOT_FOUND다")
    @Test
    void missingResourceKeepsNotFoundStatus() throws Exception {
        mvc.perform(get("/resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @DisplayName("컨트롤러 입력 제약 위반은 400이다")
    @Test
    void methodInputValidationReturnsBadRequest() throws Exception {
        mvc.perform(get("/validated-quantity").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("컨트롤러 반환값 제약 위반은 서버 오류500이다")
    @Test
    void methodReturnValidationRemainsServerError() throws Exception {
        mvc.perform(get("/validated-return"))
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

        @GetMapping("/broken-mapping")
        String brokenMapping(@PathVariable("id") String id) { return id; }

        @GetMapping("/resource")
        void resource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing.txt");
        }

        @GetMapping("/validated-quantity")
        int validatedQuantity(@RequestParam("quantity") @Min(1) int quantity) { return quantity; }

        @GetMapping("/validated-return")
        @NotBlank
        String invalidReturn() { return ""; }
    }

    record Body(Instant scheduledAt, int quantity) { }
}
