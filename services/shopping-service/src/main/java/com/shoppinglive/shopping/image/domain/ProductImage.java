package com.shoppinglive.shopping.image.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 저장된 상품 이미지의 메타데이터. 경로·URL 대신 서버가 만든 키만 둬서 저장소를 바꿔도 행은 그대로다. */
@Entity
@Table(name = "product_image")
public class ProductImage extends BaseEntity {

    @Column(nullable = false, unique = true, length = 128, updatable = false)
    private String storageKey;

    @Column(nullable = false, length = 64, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    /** 업로드한 사람이 준 파일명. 표시용일 뿐 저장 위치에 쓰지 않는다. */
    @Column(length = 255, updatable = false)
    private String originalFilename;

    protected ProductImage() {
    }

    public ProductImage(String storageKey, ImageFormat format, long sizeBytes, int width, int height,
            String originalFilename) {
        this.storageKey = storageKey;
        this.contentType = format.contentType();
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.originalFilename = originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }
}
