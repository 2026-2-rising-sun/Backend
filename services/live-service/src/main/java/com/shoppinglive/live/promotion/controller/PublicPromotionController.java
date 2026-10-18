package com.shoppinglive.live.promotion.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.promotion.dto.PublicPromotionResponse;
import com.shoppinglive.live.promotion.service.CouponPromotionService;
import com.shoppinglive.live.promotion.service.PromotionService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시청자용 공개 조회. 현재 OPEN 인 프로모션만 보여준다. */
@RestController
@RequestMapping("/v1/broadcasts/{broadcastId}/promotions")
public class PublicPromotionController {

    private final PromotionService service;
    private final CouponPromotionService coupons;
    private final ObjectMapper objectMapper;

    public PublicPromotionController(
            final PromotionService service,
            final ObjectMapper objectMapper,
            CouponPromotionService coupons) {
        this.service = service;
        this.coupons = coupons;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{promotionId}/claims")
    public ResponseEntity<ApiResponse<JsonNode>> claim(
            @PathVariable long broadcastId,
            @PathVariable long promotionId,
            @RequestHeader("Authorization") String authorization) {
        var claim = coupons.claim(broadcastId, promotionId, authorization);
        return ResponseEntity.status(claim.status()).body(ApiResponse.ok(claim.data()));
    }

    @GetMapping
    public ApiResponse<List<PublicPromotionResponse>> list(@PathVariable final long broadcastId) {
        final List<PublicPromotionResponse> items =
                service.listOpen(broadcastId).stream()
                        .map(promotion -> PublicPromotionResponse.from(promotion, objectMapper))
                        .toList();
        return ApiResponse.ok(items);
    }
}
