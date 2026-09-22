package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.application.BroadcastProductViewService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BroadcastProductViewController {
    private final BroadcastProductViewService service;

    public BroadcastProductViewController(final BroadcastProductViewService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> admin(@PathVariable final long id) {
        return ApiResponse.ok(service.forAdmin(id));
    }

    @GetMapping("/v1/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> publicView(
        @PathVariable final long id) {
        return ApiResponse.ok(service.forPublic(id));
    }
}
