package com.shoppinglive.shopping.image.infrastructure;

import com.shoppinglive.shopping.image.application.ImageStorageProperties;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 로컬 저장소의 이미지는 이 서비스의 조회 API({@code GET /v1/product-images/{id}})로 내려준다. */
@Component
@ConditionalOnProperty(name = "shopping.image.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalImageUrlResolver implements ImageUrlResolver {

    private final String baseUrl;

    public LocalImageUrlResolver(ImageStorageProperties properties) {
        this.baseUrl = properties.publicBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public String urlOf(Long imageId) {
        return baseUrl + "/v1/product-images/" + imageId;
    }
}
