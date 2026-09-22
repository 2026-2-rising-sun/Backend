package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.application.PublicBroadcastService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비회원 공개 조회. 채널 ARN·등록 키·fingerprint 는 응답에 넣지 않는다. */
@RestController
@RequestMapping("/v1/broadcasts")
public class PublicBroadcastController {
    private final PublicBroadcastService service;

    public PublicBroadcastController(final PublicBroadcastService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page<PublicBroadcastResponse>> list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PublicBroadcastResponse> detail(@PathVariable final long id) {
        return ApiResponse.ok(service.get(id));
    }
}
