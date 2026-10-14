package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.dto.PublicBroadcastResponse;
import com.shoppinglive.live.broadcast.service.PublicBroadcastService;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(
            summary = "공개 방송 목록 (#61)",
            description =
                    "로그인 없이 조회한다. LIVE → PREPARING → ENDED 그룹 순서이고 각 상태의 정렬을 쓴다.\n외부 호출 0회이므로 상품·영상 장애와 독립이다. 목록에는 videoStatus 를 채우지 않는다.")
    @GetMapping
    public ApiResponse<Page<PublicBroadcastResponse>> list(
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    /** 상세 ID 누락은 입력 오류다. 목록 URL은 /v1/broadcasts로 유지한다. */
    @Operation(
            summary = "공개 방송 목록 (#61)",
            description =
                    "로그인 없이 조회한다. LIVE → PREPARING → ENDED 그룹 순서이고 각 상태의 정렬을 쓴다.\n외부 호출 0회이므로 상품·영상 장애와 독립이다. 목록에는 videoStatus 를 채우지 않는다.")
    @GetMapping("/")
    public ApiResponse<Void> missingBroadcastId() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "broadcastId 는 필수입니다.");
    }

    @Operation(
            summary = "공개 방송 상세·시청 연결 (#61",
            description =
                    "`playbackUrl` 은 LIVE 에서만 반환한다. `playbackAllowed` 는 업무 상태이고\n`videoStatus` 는 지금 이 순간의 영상 신호다(LIVE 에서만 채운다). 둘은 분리되어 있어\nOBS 가 잠시 끊겨도 업무 상태 LIVE 는 유지된다. IVS 조회가 실패해도 이 응답은 200 이며\n`videoStatus: UNAVAILABLE` 로 표시된다. 종료된 방송은 재생 진입을 제공하지 않는다.\n시청은 IVS → FE 직접 재생이며 Live API 는 영상 프록시가 아니다.")
    @GetMapping("/{id}")
    public ApiResponse<PublicBroadcastResponse> detail(@PathVariable final long id) {
        return ApiResponse.ok(service.get(id));
    }
}
