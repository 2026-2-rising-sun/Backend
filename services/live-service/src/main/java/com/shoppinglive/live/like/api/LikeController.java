package com.shoppinglive.live.like.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.like.application.LikeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/likes")
public class LikeController {
    private final LikeService likes;

    public LikeController(final LikeService likes) {
        this.likes = likes;
    }

    @GetMapping
    public ApiResponse<LikeTotalResponse> total(@PathVariable final long id) {
        return ApiResponse.ok(likes.total(id));
    }

    /** 새 리소스를 만들지 않고 합계를 바꾼다. 그래서 201 이 아닌 200 과 조회와 같은 표현을 반환한다. */
    @PostMapping
    public ApiResponse<LikeTotalResponse> add(@PathVariable final long id) {
        return ApiResponse.ok(likes.add(id));
    }
}
