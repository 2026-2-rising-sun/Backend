package com.shoppinglive.live.promotion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.promotion.dto.PromotionInput;
import com.shoppinglive.live.promotion.dto.PromotionResponse;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.promotion.service.PromotionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/seller/broadcasts/{broadcastId}/promotions")
public class PromotionController {

    private final PromotionService service;
    private final ObjectMapper objectMapper;

    public PromotionController(final PromotionService service, final ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PromotionResponse> create(
            @PathVariable final long broadcastId,
            @RequestHeader("Idempotency-Key")
                    @Size(min = 1, max = 128, message = "Idempotency-Key는 1~128자여야 합니다.")
                    final String key,
            @Valid @RequestBody final PromotionInput input) {
        final BroadcastPromotion promotion = service.create(broadcastId, key, input);
        return ApiResponse.ok(PromotionResponse.from(promotion, objectMapper));
    }

    @GetMapping
    public ApiResponse<List<PromotionResponse>> list(@PathVariable final long broadcastId) {
        final List<PromotionResponse> items =
                service.list(broadcastId).stream()
                        .map(promotion -> PromotionResponse.from(promotion, objectMapper))
                        .toList();
        return ApiResponse.ok(items);
    }

    @PostMapping("/{promotionId}/open")
    public ApiResponse<PromotionResponse> open(
            @PathVariable final long broadcastId, @PathVariable final long promotionId) {
        final BroadcastPromotion promotion = service.open(broadcastId, promotionId);
        return ApiResponse.ok(PromotionResponse.from(promotion, objectMapper));
    }

    @PostMapping("/{promotionId}/close")
    public ApiResponse<PromotionResponse> close(
            @PathVariable final long broadcastId, @PathVariable final long promotionId) {
        final BroadcastPromotion promotion = service.close(broadcastId, promotionId);
        return ApiResponse.ok(PromotionResponse.from(promotion, objectMapper));
    }
}
