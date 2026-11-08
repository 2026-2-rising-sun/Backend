package com.shoppinglive.commercecore.storage;

import com.shoppinglive.commercecore.product.entity.ProductImage;

/** 업로드 결과. 상품 등록 때 {@code imageId} 를 대표 이미지로 넘긴다. */
public record UploadedImage(
        Long imageId, String contentType, long sizeBytes, int width, int height) {

    public static UploadedImage from(ProductImage image) {
        return new UploadedImage(
                image.getId(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight());
    }
}
