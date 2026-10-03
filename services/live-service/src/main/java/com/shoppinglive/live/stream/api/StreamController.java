package com.shoppinglive.live.stream.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import com.shoppinglive.live.stream.application.BroadcastStreamService;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StreamController {
    private final BroadcastStreamService streams;

    public StreamController(final BroadcastStreamService streams) {
        this.streams = streams;
    }

    /** X-Accel-Buffering 은 nginx 계열 프록시가 이벤트를 모아 두지 않게 한다. */
    @GetMapping("/v1/broadcasts/{id}/events")
    public ResponseEntity<SseEmitter> events(@PathVariable final long id) {
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")
            .body(streams.open(id));
    }

    /**
     * EventSource 는 Accept: text/event-stream 만 보낸다. 공통 handler 는 JSON 을 고르지 못해 500 이 되므로
     * 스트림 시작 전 오류는 Content-Type 을 직접 정해 JSON 으로 내린다.
     */
    @ExceptionHandler({BusinessException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> rejected(final Exception e) {
        final ErrorCode code = e instanceof BusinessException business ? business.errorCode() : ErrorCode.INVALID_REQUEST;
        final String message = e instanceof BusinessException ? e.getMessage() : "id 값이 올바르지 않습니다.";
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse.fail(code, message, CorrelationId.current()));
    }

    /** 스트림 시작 뒤의 전송 실패는 시청자 이탈이다. 응답을 다시 쓸 수 없고 서버 오류도 아니므로 무시한다. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    void disconnected() {
    }
}
