package com.shoppinglive.shopping.image.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.image.application.DevImageResetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 명시적으로 활성화한 local/test 전용 API. ADMIN 인가도 별도로 적용한다. */
@RestController
@Profile("(local | test) & !dev & !prod")
@ConditionalOnProperty(name = "shopping.dev-api.enabled", havingValue = "true")
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
