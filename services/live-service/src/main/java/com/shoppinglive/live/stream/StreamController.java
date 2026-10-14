package com.shoppinglive.live.stream;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private final BroadcastStreamService streams;

    public StreamController(final BroadcastStreamService streams) {
        this.streams = streams;
    }

    /** X-Accel-Buffering 은 nginx 계열 프록시가 이벤트를 모아 두지 않게 한다. */
    @Operation(
            summary = "방송 실시간 이벤트 SSE (#131)",
            description =
                    "로그인 없이 연결한다. LIVE 방송에만 연결할 수 있고 그 외 상태는 409 다.\n연결 전 오류는 `Accept` 헤더와 무관하게 JSON 오류 봉투이고, 연결 후에는 오류 본문 없이 연결이 닫힌다.\n\n이벤트(`event:` 이름과 `data:` JSON. data 는 `ApiResponse` 봉투가 아니다):\n\n| event | data | 시점 |\n|---|---|---|\n| `stream.ready` | `StreamReadyEvent` | 연결 직후 한 번 |\n| `chat.created` | `ChatMessage` (`id:` 는 messageId) | 채팅 저장 후 |\n| `likes.updated` | `LikeTotal` | 커밋 version이 바뀌면 약 1초 간격 |\n| `broadcast.ended` | `BroadcastEndedEvent` | 방송 종료 시. 이후 서버가 연결을 닫는다 |\n\n클라이언트 규칙:\n- `stream.ready` 를 받은 뒤 최근 채팅과 좋아요 합계를 GET 으로 조회하고, 채팅은 messageId 로 중복을 제거한다.\n- `broadcast.ended` 를 받으면 재연결하지 않고 마지막 좋아요 합계를 GET으로 조회한다. 받지 못하고 끊겼다면 재연결이 409 일 때 방송 상태와 합계를 조회한다.\n- 전달은 보장하지 않는다. `Last-Event-ID` 로 놓친 이벤트를 다시 보내지 않는다. 재연결 후 최근 50건으로 복구한다.\n- 약 15초마다 comment 줄(`:heartbeat`)이 온다. 수신이 느려 대기량을 넘으면 서버가 연결을 닫는다.\n- 서버는 연결을 최대 30분 유지한다. 닫히면 다시 연결한다. 재연결이 409 이면 방송 상태를 조회한다.")
    @GetMapping("/v1/broadcasts/{id}/events")
    public ResponseEntity<SseEmitter> events(@PathVariable final long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(streams.open(id));
    }

    /**
     * EventSource 는 Accept: text/event-stream 만 보낸다. 공통 handler 는 JSON 을 고르지 못해 500 이 되므로 스트림 시작 전
     * 오류는 Content-Type 을 직접 정해 JSON 으로 내린다.
     */
    @ExceptionHandler({BusinessException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> rejected(final Exception e) {
        final ErrorCode code =
                e instanceof BusinessException business
                        ? business.errorCode()
                        : ErrorCode.INVALID_REQUEST;
        final String message = e instanceof BusinessException ? e.getMessage() : "id 값이 올바르지 않습니다.";
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(code, message, CorrelationId.current()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(final Exception e) {
        log.error("stream could not start", e);
        final ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(code, code.defaultMessage(), CorrelationId.current()));
    }

    /** 스트림 시작 뒤의 전송 실패는 시청자 이탈이다. 응답을 다시 쓸 수 없고 서버 오류도 아니므로 무시한다. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    void disconnected() {}
}
