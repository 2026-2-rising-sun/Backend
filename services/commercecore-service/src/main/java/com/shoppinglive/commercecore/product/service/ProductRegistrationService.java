package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.RegisterProductCommand;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.commercecore.product.repository.ProductImageRepository;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 상품 기본정보 등록. 같은 이름의 상품은 여러 개 허용한다 (판매자가 구분할 일). */
@Service
public class ProductRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationService.class);
    private static final int NAME_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;
    static final String MAIN_IMAGE_NOT_FOUND = "대표 이미지를 찾을 수 없습니다. 이미지를 다시 업로드해 주세요.";

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    private final TransactionTemplate transactions;
    private final ProductInfoEventPublisher events;

    public ProductRegistrationService(
            ProductRepository productRepository,
            ProductImageRepository productImageRepository,
            TransactionTemplate transactions,
            ProductInfoEventPublisher events) {
        this.transactions = transactions;
        this.events = events;
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
    }

    /** 상품과 Outbox는 함께 커밋한다. 동시 멱등키 충돌 후 재조회는 실패한 트랜잭션 밖에서 한다. */
    public Product register(
            RegisterProductCommand command, String idempotencyKey, String sellerId) {
        if (sellerId == null || sellerId.isBlank() || sellerId.length() > 36) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매자 식별자가 올바르지 않습니다.");
        }
        String name = requireName(command.name());
        String description = requireDescription(command.description());

        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Optional<Product> existing = productRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return requireSameSeller(existing.get(), sellerId);
        }
        try {
            return transactions.execute(
                    status -> {
                        if (command.mainImageId() == null
                                || !productImageRepository.existsById(command.mainImageId())) {
                            throw new BusinessException(
                                    ErrorCode.INVALID_REQUEST, MAIN_IMAGE_NOT_FOUND);
                        }
                        Product product =
                                productRepository.saveAndFlush(
                                        new Product(
                                                name,
                                                description,
                                                command.mainImageId(),
                                                idempotencyKey,
                                                sellerId,
                                                command.category()));
                        events.publish(product);
                        return product;
                    });
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 커밋한 경우만 기존 결과를 반환한다.
            return productRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .map(
                            winner -> {
                                log.info("동시 상품 등록 요청의 기존 결과 반환: 멱등성 키={}", idempotencyKey);
                                return requireSameSeller(winner, sellerId);
                            })
                    .orElseThrow(() -> e);
        }
    }

    private static Product requireSameSeller(Product product, String sellerId) {
        if (!sellerId.equals(product.getSellerId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "다른 상품 등록에 사용된 멱등키입니다.");
        }
        return product;
    }

    /** 수정에서도 같은 규칙을 쓴다. */
    static String requireName(String value) {
        return requireText(value, "상품명", NAME_MAX_LENGTH);
    }

    static String requireDescription(String value) {
        return requireText(value, "상품 설명", DESCRIPTION_MAX_LENGTH);
    }

    /** 앞뒤 공백을 제거한 뒤 1~max 자인지 확인한다. */
    private static String requireText(String value, String field, int maxLength) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, field + "은(는) 1~" + maxLength + "자로 입력해 주세요.");
        }
        return stripped;
    }
}
