package com.shoppinglive.common.observability;

import com.shoppinglive.common.web.CorrelationId;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 본문·쿼리 값 대신 매핑 경로와 완료 결과만 남긴다. SSE는 실제 종료 시 한 번 기록한다. */
public class RequestLogFilter extends OncePerRequestFilter implements Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String requestId = response.getHeader(CorrelationId.HEADER);
        String traceId = MDC.get("trace_id");
        String spanId = MDC.get("span_id");
        AtomicBoolean logged = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        Runnable completed =
                () -> {
                    if (!logged.compareAndSet(false, true)) return;
                    Object route =
                            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    log.atInfo()
                            .addKeyValue("event", "http_completed")
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("trace_id", traceId)
                            .addKeyValue("span_id", spanId)
                            .addKeyValue("method", request.getMethod())
                            .addKeyValue("route", route == null ? "UNMAPPED" : route.toString())
                            .addKeyValue("status", failed.get() ? 500 : response.getStatus())
                            .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                            .log("HTTP 요청 처리 종료");
                };
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            failed.set(true);
            throw failure;
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext()
                            .addListener(
                                    new AsyncListener() {
                                        @Override
                                        public void onComplete(AsyncEvent event) {
                                            completed.run();
                                        }

                                        @Override
                                        public void onTimeout(AsyncEvent event) {
                                            /* 최종 완료에서 기록 */
                                        }

                                        @Override
                                        public void onError(AsyncEvent event) {
                                            /* 최종 완료에서 기록 */
                                        }

                                        @Override
                                        public void onStartAsync(AsyncEvent event) {
                                            event.getAsyncContext().addListener(this);
                                        }
                                    });
                } catch (IllegalStateException alreadyCompleted) {
                    completed.run();
                }
            } else {
                completed.run();
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
