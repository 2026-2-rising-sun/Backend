package com.shoppinglive.shopping.image.api;

import com.shoppinglive.shopping.image.application.UploadedImage;

public record ProductImageUploadResponse(Long imageId, String url, String contentType, long sizeBytes, int width,
        int height) {

    static ProductImageUploadResponse of(UploadedImage image, String url) {
        return new ProductImageUploadResponse(image.imageId(), url, image.contentType(), image.sizeBytes(),
                image.width(), image.height());
    }
}
