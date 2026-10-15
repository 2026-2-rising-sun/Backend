package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.dto.PublicBroadcastResponse;
import com.shoppinglive.live.broadcast.service.PublicBroadcastService;
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

    /** 상세 ID 누락은 입력 오류다. 목록 URL은 /v1/broadcasts로 유지한다. */
    @GetMapping("/")
    public ApiResponse<Void> missingBroadcastId() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "broadcastId 는 필수입니다.");
    }

    @GetMapping("/{id}")
    public ApiResponse<PublicBroadcastResponse> detail(@PathVariable final long id) {
        return ApiResponse.ok(service.get(id));
    }
}
