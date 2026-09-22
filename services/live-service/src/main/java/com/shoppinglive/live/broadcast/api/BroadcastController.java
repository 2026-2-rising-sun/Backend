package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/broadcasts")
public class BroadcastController {
    private final BroadcastService service;

    public BroadcastController(final BroadcastService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BroadcastResponse> register(
        @RequestHeader("Idempotency-Key")
        @Size(min = 1, max = 128, message = "Idempotency-Key는 1~128자여야 합니다.")
        final String key,
        @Valid @RequestBody final BroadcastInput input) {
        return ApiResponse.ok(BroadcastResponse.from(service.register(key, input)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<BroadcastResponse> edit(
        @PathVariable final long id,
        @RequestParam @NotNull(message = "version 파라미터는 필수입니다.")
        final long version,
        @Valid @RequestBody final BroadcastPatchInput input) {
        return ApiResponse.ok(BroadcastResponse.from(service.edit(id, version, input)));
    }

    @PostMapping("/{id}/end")
    public ApiResponse<BroadcastResponse> end(@PathVariable final long id) {
        return ApiResponse.ok(BroadcastResponse.from(service.end(id)));
    }

    @GetMapping
    public ApiResponse<Page<BroadcastResponse>> list(
        @RequestParam(required = false) final BroadcastStatus status,
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size) {
        return ApiResponse.ok(service.list(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<BroadcastResponse> detail(@PathVariable final long id) {
        return ApiResponse.ok(BroadcastResponse.from(service.get(id)));
    }
}
