package com.shoppinglive.shopping.image.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.image.application.DevImageResetService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 개발 전용 이미지 초기화 API. local 프로필이 아니면 매핑 자체가 없다. */
@RestController
@Profile("local")
@RequestMapping("/v1/dev/product-images")
public class DevProductImageController {

    private final DevImageResetService devImageResetService;

    public DevProductImageController(DevImageResetService devImageResetService) {
        this.devImageResetService = devImageResetService;
    }

    @DeleteMapping
    public ApiResponse<DevImageResetResponse> deleteUnreferenced() {
        return ApiResponse.ok(new DevImageResetResponse(devImageResetService.deleteUnreferencedImages()));
    }

    public record DevImageResetResponse(int deletedCount) {
    }
}
