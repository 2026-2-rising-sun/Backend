package com.shoppinglive.shopping.product.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 상품 기본정보 등록. 같은 이름의 상품은 여러 개 허용한다 (판매자가 구분할 일).
 */
@Service
public class ProductRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationService.class);
    private static final int NAME_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    public ProductRegistrationService(ProductRepository productRepository,
            ProductImageRepository productImageRepository) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
    }

    /**
     * 같은 멱등 키로 이미 등록된 상품이 있으면 새로 만들지 않고 그 상품을 돌려준다 (재전송 재생).
     *
     * <p>메서드에 트랜잭션을 걸지 않는다. {@code saveAndFlush} 가 자체 트랜잭션에서 바로 커밋해야, 같은 키의
     * 동시 요청이 UNIQUE 제약에 걸렸을 때 여기서 잡고 먼저 커밋된 상품을 다시 읽을 수 있다. 바깥 트랜잭션이
     * 있으면 예외 후 그 트랜잭션이 rollback-only 가 되어 재조회 결과를 돌려줄 수 없다.
     */
    public Product register(RegisterProductCommand command, String idempotencyKey) {
        String name = requireText(command.name(), "상품명", NAME_MAX_LENGTH);
        String description = requireText(command.description(), "상품 설명", DESCRIPTION_MAX_LENGTH);

        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Optional<Product> existing = productRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (command.mainImageId() == null || !productImageRepository.existsById(command.mainImageId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "대표 이미지를 찾을 수 없습니다. 이미지를 다시 업로드해 주세요.");
        }
        try {
            return productRepository.saveAndFlush(new Product(name, description, command.mainImageId(), idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 커밋한 경우. 그 밖의 제약 위반(예: 이미지가 그사이 삭제)은 그대로 던진다.
            return productRepository.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> {
                        log.info("concurrent product registration replayed: key={}", idempotencyKey);
                        return winner;
                    })
                    .orElseThrow(() -> e);
        }
    }

    /** 앞뒤 공백을 제거한 뒤 1~max 자인지 확인한다. */
    private static String requireText(String value, String field, int maxLength) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + "은(는) 1~" + maxLength + "자로 입력해 주세요.");
        }
        return stripped;
    }
}
