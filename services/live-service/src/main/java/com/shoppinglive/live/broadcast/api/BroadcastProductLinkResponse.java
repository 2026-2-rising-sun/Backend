package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.live.broadcast.domain.BroadcastProduct;

/** productId(Shopping)와 salesId(Commerce)를 구분해 반환한다. */
public record BroadcastProductLinkResponse(Long linkId, Long productId, Long salesId,
                                           int position, long broadcastVersion) {
    public static BroadcastProductLinkResponse from(final BroadcastProduct link) {
        return new BroadcastProductLinkResponse(link.getId(), link.getProductId(),
            link.getSalesId(), link.getPosition(), link.getBroadcast().getVersion());
    }
}
