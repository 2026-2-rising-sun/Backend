package com.shoppinglive.live.promotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.client.coupon.PromotionCouponClient;
import com.shoppinglive.live.promotion.dto.PromotionInput;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.promotion.entity.PromotionStatus;
import com.shoppinglive.live.promotion.repository.BroadcastPromotionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 외부 쿠폰 조회·발급은 방송 행 잠금을 잡는 프로모션 전이 트랜잭션 밖에서 수행한다. */
@Service
public class CouponPromotionService {
    private final PromotionService service;
    private final BroadcastRepository broadcasts;
    private final BroadcastPromotionRepository promotions;
    private final PromotionCouponClient coupons;
    private final ObjectMapper mapper;

    public CouponPromotionService(
            PromotionService service,
            BroadcastRepository broadcasts,
            BroadcastPromotionRepository promotions,
            PromotionCouponClient coupons,
            ObjectMapper mapper) {
        this.service = service;
        this.broadcasts = broadcasts;
        this.promotions = promotions;
        this.coupons = coupons;
        this.mapper = mapper;
    }

    public BroadcastPromotion create(
            long broadcastId, String key, PromotionInput input, String authorization) {
        if ("COUPON_DROP".equals(input.type()))
            coupons.sellerCoupon(authorization, couponId(input.attributes()));
        return service.create(broadcastId, key, input);
    }

    public BroadcastPromotion open(long broadcastId, long promotionId, String authorization) {
        var promotion = find(broadcastId, promotionId);
        // 이미 열린 이벤트 재요청은 이후 쿠폰 소진·기간 변경과 무관하게 기존 전이를 재사용한다.
        if ("COUPON_DROP".equals(promotion.getType())
                && promotion.getStatus() == PromotionStatus.READY) {
            var coupon = coupons.sellerCoupon(authorization, couponId(attributes(promotion)));
            try {
                Instant now = Instant.now();
                if (now.isBefore(Instant.parse(coupon.path("startsAt").asText()))
                        || !now.isBefore(Instant.parse(coupon.path("endsAt").asText()))
                        || coupon.path("issuedCount").asInt()
                                >= coupon.path("issuanceLimit").asInt()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "현재 발급 가능한 쿠폰만 이벤트를 열 수 있습니다.");
                }
            } catch (java.time.DateTimeException error) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "쿠폰 발급 기간을 확인할 수 없습니다.");
            }
        }
        return service.open(broadcastId, promotionId);
    }

    public PromotionCouponClient.Claim claim(
            long broadcastId, long promotionId, String authorization) {
        var broadcast =
                broadcasts
                        .findById(broadcastId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        var promotion = find(broadcastId, promotionId);
        if (broadcast.getStatus() != BroadcastStatus.LIVE
                || promotion.getStatus() != PromotionStatus.OPEN
                || (promotion.getClosesAt() != null
                        && !Instant.now().isBefore(promotion.getClosesAt()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송의 열린 이벤트에서만 쿠폰을 받을 수 있습니다.");
        }
        if (!"COUPON_DROP".equals(promotion.getType()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "쿠폰 이벤트가 아닙니다.");
        return coupons.claim(authorization, couponId(attributes(promotion)));
    }

    private BroadcastPromotion find(long broadcastId, long promotionId) {
        return promotions
                .findByIdAndBroadcastId(promotionId, broadcastId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "프로모션을 찾을 수 없습니다."));
    }

    private Map<String, Object> attributes(BroadcastPromotion promotion) {
        try {
            return mapper.readValue(
                    promotion.getAttributes(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "쿠폰 이벤트 정보를 확인할 수 없습니다.");
        }
    }

    private String couponId(Map<String, Object> attributes) {
        Object value = attributes == null ? null : attributes.get("couponPolicyId");
        if (!(value instanceof String id)
                || !id.matches(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "couponPolicyId에는 쿠폰 UUID가 필요합니다.");
        }
        return id;
    }
}
