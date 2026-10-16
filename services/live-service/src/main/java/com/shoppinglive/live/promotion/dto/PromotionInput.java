package com.shoppinglive.live.promotion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** COUPON_DROP은 attributes.couponPolicyId의 기존 쿠폰 UUID를 참조한다. 나머지 유형의 메타데이터는 저장·전달만 한다. */
public record PromotionInput(
        @NotBlank(message = "type은 필수입니다.")
                @Pattern(
                        regexp = "^[A-Z][A-Z0-9_]{1,63}$",
                        message = "type은 2~64자의 대문자 영숫자·언더스코어로 시작해야 합니다.")
                String type,
        @NotBlank(message = "title은 필수입니다.")
                @Size(min = 1, max = 100, message = "title은 1~100자여야 합니다.")
                String title,
        Map<String, Object> attributes,
        @Min(value = 10, message = "durationSeconds는 10초 이상이어야 합니다.")
                @Max(value = 3600, message = "durationSeconds는 3600초 이하여야 합니다.")
                Integer durationSeconds) {}
