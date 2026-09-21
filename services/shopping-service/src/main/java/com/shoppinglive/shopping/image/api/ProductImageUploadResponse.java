package com.shoppinglive.shopping.image.api;

import com.shoppinglive.shopping.image.application.UploadedImage;

/** URL 은 저장소 교체에 대비해 조회 시점에 만들 예정이라 업로드 응답에 넣지 않는다. */
public record ProductImageUploadResponse(Long imageId, String contentType, long sizeBytes, int width, int height) {

    static ProductImageUploadResponse from(UploadedImage image) {
        return new ProductImageUploadResponse(image.imageId(), image.contentType(), image.sizeBytes(),
                image.width(), image.height());
    }
}
