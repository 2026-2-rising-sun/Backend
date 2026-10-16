package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.dto.BroadcastProductLinkResponse;
import com.shoppinglive.live.broadcast.dto.LinkProductInput;
import com.shoppinglive.live.broadcast.dto.ReorderProductsInput;
import com.shoppinglive.live.broadcast.entity.BroadcastProduct;
import com.shoppinglive.live.broadcast.service.BroadcastProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/seller/broadcasts/{id}/products")
public class BroadcastProductController {
    private final BroadcastProductService service;

    public BroadcastProductController(final BroadcastProductService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BroadcastProductLinkResponse> link(
            @PathVariable final long id, @Valid @RequestBody final LinkProductInput input) {
        final BroadcastProduct link = service.link(id, input.productId(), input.expectedVersion());
        return ApiResponse.ok(BroadcastProductLinkResponse.from(link));
    }

    @PutMapping("/order")
    public ApiResponse<List<BroadcastProductLinkResponse>> reorder(
            @PathVariable final long id, @Valid @RequestBody final ReorderProductsInput input) {
        return ApiResponse.ok(
                service.reorder(id, input.linkIds(), input.expectedVersion()).stream()
                        .map(BroadcastProductLinkResponse::from)
                        .toList());
    }

    /** 성공은 204 무본문이다. */
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> unlink(
            @PathVariable final long id,
            @PathVariable final long linkId,
            @RequestParam @PositiveOrZero(message = "0 이상이어야 합니다.") final long expectedVersion) {
        service.unlink(id, linkId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
