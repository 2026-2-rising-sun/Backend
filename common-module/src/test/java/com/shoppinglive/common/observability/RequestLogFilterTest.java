package com.shoppinglive.common.observability;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

class RequestLogFilterTest {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    RequestLogFilter filter = new RequestLogFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders/123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void prepare() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("완료 로그는 경로 템플릿과 응답 상태를 기록하고 본문과 쿼리를 제외한다")
    void recordsSafeCompletion() throws Exception {
        request.setQueryString("token=private");
        request.setContent("secret-body".getBytes());
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    req.setAttribute(
                            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
                    response.setStatus(201);
                });
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getKeyValuePairs().toString())
                .contains("route=\"/orders/{id}\"", "status=\"201\"")
                .doesNotContain("private", "secret-body", "/123");
    }

    @Test
    @DisplayName("처리되지 않은 예외는 성공으로 기록하지 않고 호출자에게 그대로 전달한다")
    void recordsUnhandledFailure() {
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (req, res) -> {
                                            throw new ServletException("private");
                                        }))
                .isInstanceOf(ServletException.class);
        assertThat(logs.list.getFirst().getKeyValuePairs().toString()).contains("status=\"500\"");
    }

    @Test
    @DisplayName("비동기 요청은 응답 시작이 아닌 완료 시 한 번 기록한다")
    void waitsForAsyncCompletion() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        assertThat(logs.list).isEmpty();
        request.getAsyncContext().complete();
        assertThat(logs.list).hasSize(1);
    }
}
