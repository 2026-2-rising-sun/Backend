package com.shoppinglive.live.integration.shopping;

/** Shopping 경계 DTO. mainImageUrl null 은 기본 이미지 표시를 뜻한다. */
public record ProductSnapshot(Long id, String name, String mainImageUrl) {
    public ProductSnapshot {
        if (id == null || id < 1 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Invalid product snapshot");
        }
    }
}
