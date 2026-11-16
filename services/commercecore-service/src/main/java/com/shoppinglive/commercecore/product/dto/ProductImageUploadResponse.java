package com.shoppinglive.commercecore.product.dto;

import com.shoppinglive.commercecore.storage.UploadedImage;

public record ProductImageUploadResponse(
        Long imageId, String url, String contentType, long sizeBytes, int width, int height) {

    public static ProductImageUploadResponse of(UploadedImage image, String url) {
        return new ProductImageUploadResponse(
                image.imageId(),
                url,
                image.contentType(),
                image.sizeBytes(),
                image.width(),
                image.height());
    }
}
