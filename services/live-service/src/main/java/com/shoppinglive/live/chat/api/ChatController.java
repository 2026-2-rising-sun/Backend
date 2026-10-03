package com.shoppinglive.live.chat.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.live.chat.application.ChatQueryService;
import com.shoppinglive.live.chat.application.ChatWriteService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping
    public ApiResponse<List<ChatMessageResponse>> recent(@PathVariable final long id) {
        return ApiResponse.ok(queries.recent(id));
    }

    /** 작성자는 인증 principal 에서 얻는다. Authorization 헤더는 Member 이름 조회에만 전달한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatMessageResponse> write(@PathVariable final long id,
        @AuthenticationPrincipal final AuthenticatedUser user,
        @RequestHeader(HttpHeaders.AUTHORIZATION) final String authorization,
        @Valid @RequestBody final ChatInput input) {
        return ApiResponse.ok(writes.write(id, user.memberId(), authorization, input.content()));
    }
}
