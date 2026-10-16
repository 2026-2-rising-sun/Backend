package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.dto.BroadcastProductViewResponse;
import com.shoppinglive.live.broadcast.service.BroadcastProductViewService;
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

    @GetMapping("/v1/seller/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> seller(@PathVariable final long id) {
        return ApiResponse.ok(service.forSeller(id));
    }

    @GetMapping("/v1/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> publicView(@PathVariable final long id) {
        return ApiResponse.ok(service.forPublic(id));
    }
}
