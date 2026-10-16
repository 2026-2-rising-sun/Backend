package com.shoppinglive.live.viewer.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.viewer.dto.ViewerHeartbeat;
import com.shoppinglive.live.viewer.dto.ViewerTotal;
import com.shoppinglive.live.viewer.service.ViewerPresenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/viewers")
public class ViewerController {
    private final ViewerPresenceService viewers;

    public ViewerController(ViewerPresenceService viewers) {
        this.viewers = viewers;
    }

    @GetMapping
    public ApiResponse<ViewerTotal> total(@PathVariable long id) {
        return ApiResponse.ok(viewers.total(id));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<ViewerTotal> heartbeat(
            @PathVariable long id, @Valid @RequestBody ViewerHeartbeat input) {
        return ApiResponse.ok(viewers.heartbeat(id, input.viewerId()));
    }
}
