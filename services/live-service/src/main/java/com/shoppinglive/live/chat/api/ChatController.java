package com.shoppinglive.live.chat.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.chat.application.ChatQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/chats")
public class ChatController {
    private final ChatQueryService queries;

    public ChatController(final ChatQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<List<ChatMessageResponse>> recent(@PathVariable final long id) {
        return ApiResponse.ok(queries.recent(id));
    }
}
