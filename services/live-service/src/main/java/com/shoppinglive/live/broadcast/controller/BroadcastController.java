package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.dto.BroadcastPatchInput;
import com.shoppinglive.live.broadcast.dto.BroadcastResponse;
import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.service.BroadcastEndNotifier;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.broadcast.service.BroadcastStartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/broadcasts")
public class BroadcastController {
    private final BroadcastService service;
    private final BroadcastStartService startService;
    private final BroadcastEndNotifier endNotifier;

    public BroadcastController(
            final BroadcastService service,
            final BroadcastStartService startService,
            final BroadcastEndNotifier endNotifier) {
        this.service = service;
        this.startService = startService;
        this.endNotifier = endNotifier;
    }

    @Operation(
            summary = "방송 등록 (#59)",
            description =
                    "PREPARING 으로 저장한다. 과거 예정 시각도 허용하며 AWS 송출 여부는 조회하지 않는다.\n`Idempotency-Key` 는 방송 레코드 수명 동안 unique 하다. 같은 키·동일 입력은 같은 id 를\n돌려주고, 같은 키·다른 입력은 409 다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BroadcastResponse> register(
            @RequestHeader("Idempotency-Key")
                    @Size(min = 1, max = 128, message = "Idempotency-Key는 1~128자여야 합니다.")
                    final String key,
            @Valid @RequestBody final BroadcastInput input) {
        return ApiResponse.ok(BroadcastResponse.from(service.register(key, input)));
    }

    /** 상세 ID 누락 검사는 기존 SELLER 인가 검사 이후에 수행한다. */
    @Operation(
            summary = "관리 방송 목록 (#60)",
            description =
                    "상품·IVS 호출 없이 기본정보만 읽는다. 기본 정렬은 createdAt DESC, id DESC 이고\n상태 필터는 준비 scheduledAt ASC, 진행 startedAt DESC, 종료 endedAt DESC 다.")
    @SecurityRequirement(name = "BearerAuth")
    @RequestMapping(
            path = "/",
            method = {RequestMethod.GET, RequestMethod.PATCH})
    public ApiResponse<Void> missingBroadcastId() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "broadcastId 는 필수입니다.");
    }

    @Operation(
            summary = "방송 기본정보 수정 (#59)",
            description =
                    "PREPARING 에서만 허용한다. 생략한 필드는 기존 값을 유지하고 본문에 명시적으로 null 을\n준 필드는 400 이다. `channelArn` 과 `playbackUrl` 은 함께 변경해야 한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PatchMapping("/{id}")
    public ApiResponse<BroadcastResponse> edit(
            @PathVariable final long id,
            @RequestParam
                    @NotNull(message = "version 파라미터는 필수입니다.")
                    @PositiveOrZero(message = "version은 0 이상이어야 합니다.")
                    final long version,
            @Valid @RequestBody final BroadcastPatchInput input) {
        return ApiResponse.ok(BroadcastResponse.from(service.edit(id, version, input)));
    }

    @Operation(
            summary = "방송 시작 (#65)",
            description =
                    "IVS LIVE+HEALTHY 와 채널-시청 URL 동일성, 현재 ON_SALE/SOLD_OUT 연결 상품 최소 1개를\n확인한 뒤 LIVE 로 전환한다. available 재고는 시작 판단에 쓰지 않는다.\n이미 LIVE 면 외부 재확인 없이 최초 startedAt 을 보존해 성공하고 ENDED 재시작은 409 다.\n같은 channelArn 으로 이미 진행 중인 방송이 있으면 409 다(DB partial unique index).")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/start")
    public ApiResponse<BroadcastResponse> start(
            @PathVariable final long id,
            @RequestParam @PositiveOrZero(message = "0 이상이어야 합니다.") final long expectedVersion) {
        return ApiResponse.ok(BroadcastResponse.from(startService.start(id, expectedVersion)));
    }

    @Operation(
            summary = "방송 종료 (#66)",
            description =
                    "LIVE 를 ENDED 로 바꾸고 최초 endedAt 을 보존한다. 반복 종료는 성공하고 준비 종료는 409 다.\n결제·주문·판매 상태·재고에 영향을 주지 않으며 OBS 는 운영자가 수동으로 중단한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/{id}/end")
    public ApiResponse<BroadcastResponse> end(@PathVariable final long id) {
        final Broadcast ended = service.end(id);
        endNotifier.ended(ended);
        return ApiResponse.ok(BroadcastResponse.from(ended));
    }

    @Operation(
            summary = "관리 방송 목록 (#60)",
            description =
                    "상품·IVS 호출 없이 기본정보만 읽는다. 기본 정렬은 createdAt DESC, id DESC 이고\n상태 필터는 준비 scheduledAt ASC, 진행 startedAt DESC, 종료 endedAt DESC 다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping
    public ApiResponse<Page<BroadcastResponse>> list(
            @RequestParam(required = false) final BroadcastStatus status,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        return ApiResponse.ok(service.list(status, page, size));
    }

    @Operation(summary = "관리 방송 상세 (#60)")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/{id}")
    public ApiResponse<BroadcastResponse> detail(@PathVariable final long id) {
        return ApiResponse.ok(BroadcastResponse.from(service.get(id)));
    }
}
