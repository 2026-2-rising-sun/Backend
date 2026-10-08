package com.shoppinglive.live.like.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.live.like.application.LikeService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/likes")
public class LikeController {
    private final LikeService likes;
    public LikeController(LikeService likes) { this.likes = likes; }

    @GetMapping
    public ApiResponse<LikeTotalResponse> total(@PathVariable long id) {
        return ApiResponse.ok(likes.total(id));
    }

    @GetMapping("/mine")
    public ApiResponse<MemberLikeResponse> mine(@PathVariable long id,
        @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(likes.mine(id, user.memberId()));
    }

    @PutMapping("/mine")
    public ApiResponse<MemberLikeResponse> set(@PathVariable long id,
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LikeInput input) {
        return ApiResponse.ok(likes.set(id, user.memberId(), key, input.liked()));
    }
}
