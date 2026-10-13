package com.shoppinglive.live.promotion.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.promotion.application.PromotionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시청자용 공개 조회. 현재 OPEN 인 프로모션만 보여준다. */
@RestController
@RequestMapping("/v1/broadcasts/{broadcastId}/promotions")
public class PublicPromotionController {

    private final PromotionService service;
    private final ObjectMapper objectMapper;

    public PublicPromotionController(final PromotionService service, final ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<PublicPromotionResponse>> list(@PathVariable final long broadcastId) {
        final List<PublicPromotionResponse> items = service.listOpen(broadcastId).stream()
            .map(promotion -> PublicPromotionResponse.from(promotion, objectMapper))
            .toList();
        return ApiResponse.ok(items);
    }
}
