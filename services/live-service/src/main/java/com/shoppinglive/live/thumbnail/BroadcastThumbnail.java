package com.shoppinglive.live.thumbnail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** 썸네일은 작은 이미지로 제한하고 메타데이터와 함께 저장해 재배포 시에도 보존한다. */
@Entity
@Table(name = "broadcast_thumbnail")
public class BroadcastThumbnail {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String sellerId;

    @Column(nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    protected BroadcastThumbnail() {}

    public BroadcastThumbnail(String sellerId, String contentType, byte[] content) {
        this.id = UUID.randomUUID().toString();
        this.sellerId = sellerId;
        this.contentType = contentType;
        this.content = content.clone();
    }

    public String getId() {
        return id;
    }

    public String getSellerId() {
        return sellerId;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content.clone();
    }
}
