package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.live.broadcast.dto.ChatInput;
import com.shoppinglive.live.broadcast.dto.ChatMessageResponse;
import com.shoppinglive.live.broadcast.service.ChatQueryService;
import com.shoppinglive.live.broadcast.service.ChatWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/chats")
public class ChatController {
    private final ChatQueryService queries;
    private final ChatWriteService writes;

    public ChatController(final ChatQueryService queries, final ChatWriteService writes) {
        this.queries = queries;
        this.writes = writes;
    }

    @Operation(
            summary = "최근 채팅 조회 (#129)",
            description =
                    "로그인 없이 조회한다. 최신 50건을 골라 오래된 순서로 반환한다. 작성 시각이 같으면\nmessageId 순서다. PREPARING 방송은 빈 배열이고 ENDED 방송도 조회할 수 있다.\n페이지 이동은 제공하지 않는다. 재접속 복구 범위는 이 50건이다.")
    @GetMapping
    public ApiResponse<List<ChatMessageResponse>> recent(@PathVariable final long id) {
        return ApiResponse.ok(queries.recent(id));
    }

    /** 작성자는 인증 principal 에서 얻는다. Authorization 헤더는 Member 이름 조회에만 전달한다. */
    @Operation(
            summary = "채팅 작성 (#130)",
            description =
                    "LIVE 방송에만 쓸 수 있다. 작성자는 인증 정보에서 얻으며 요청 본문의 회원 ID·이름은 받지 않는다.\n표시 이름은 작성 시점의 Member 값을 저장한다. `content` 는 앞뒤 공백을 제거한 뒤\nUnicode code point 기준 1~200자다. 줄바꿈을 허용하며 HTML 로 해석하지 않는다.\n멱등 키가 없으므로 재시도는 새 채팅을 만든다. 방송 종료와 동시에 도착한 요청은 저장될 수 있다.\n저장 후 SSE `chat.created` 로 전달을 시도한다. 전달에 실패해도 응답은 201 이고 채팅은 최근 내역에서 조회된다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatMessageResponse> write(
            @PathVariable final long id,
            @AuthenticationPrincipal final AuthenticatedUser user,
            @RequestHeader(HttpHeaders.AUTHORIZATION) final String authorization,
            @Valid @RequestBody final ChatInput input) {
        return ApiResponse.ok(writes.write(id, user.memberId(), authorization, input.content()));
    }
}
