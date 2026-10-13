package com.shoppinglive.live.promotion.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 프로모션 생성 입력. {@code attributes} 는 라이브가 해석하지 않는 자유 JSON 객체다.
 * 쿠폰 전용 예: {@code {"couponPolicyId": 7, "claimLimit": 500}}.
 *
 * @param type             대문자 영숫자 상수 이름(예: {@code COUPON_DROP}, {@code TIME_DEAL})
 * @param title            시청자에게 보이는 짧은 제목
 * @param attributes       종류별 상세 필드. null 이면 빈 Map 으로 간주한다
 * @param durationSeconds  자동 만료 시간(초). null 이면 자동 만료 안 함. 10~3600 범위
 */
public record PromotionInput(
        @NotBlank(message = "type은 필수입니다.")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$",
            message = "type은 2~64자의 대문자 영숫자·언더스코어로 시작해야 합니다.")
        String type,
        @NotBlank(message = "title은 필수입니다.")
        @Size(min = 1, max = 100, message = "title은 1~100자여야 합니다.")
        String title,
        Map<String, Object> attributes,
        @Min(value = 10, message = "durationSeconds는 10초 이상이어야 합니다.")
        @Max(value = 3600, message = "durationSeconds는 3600초 이하여야 합니다.")
        Integer durationSeconds) {
}
