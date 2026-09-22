package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.product.application.ProductListPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 공개 목록 커서 문자열. 클라이언트가 내부 구조에 기대지 않도록 {@code <createdAt ISO-8601>_<id>} 를
 * base64url 로 감싼다. ISO 문자열은 나노초까지 그대로 담아 DB 값과 정확히 같게 비교되게 한다.
 */
final class PublicProductCursor {

    private static final char SEPARATOR = '_';

    private PublicProductCursor() {
    }

    static String encode(ProductListPosition position) {
        String raw = position.createdAt().toString() + SEPARATOR + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws BusinessException INVALID_REQUEST — 위조·손상된 커서 */
    static ProductListPosition decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf(SEPARATOR);
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            return new ProductListPosition(createdAt, id);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.");
        }
    }
}
