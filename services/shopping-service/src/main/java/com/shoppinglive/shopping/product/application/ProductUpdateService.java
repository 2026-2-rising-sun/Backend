package com.shoppinglive.shopping.product.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import java.util.Objects;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 기본정보 수정과 대표 이미지 교체.
 *
 * <p>가격·재고·판매 상태는 Commerce 소유라 건드리지 않는다. 이미 생성된 주문의 상품명은 Commerce 가 주문 시점에
 * 스냅샷으로 저장하므로 여기서 이름을 바꿔도 영향이 없다.
 */
@Service
public class ProductUpdateService {

    public static final String CONFLICT_MESSAGE = "다른 수정이 먼저 반영되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.";

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    public ProductUpdateService(ProductRepository productRepository,
            ProductImageRepository productImageRepository) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
    }

    /**
     * 요청 버전이 현재 버전과 다르면 다른 수정을 덮어쓰지 않도록 409 로 거절한다. 확인과 저장 사이에 끼어든 수정은
     * {@code @Version} 이 flush 시점에 잡아 같은 409 가 된다.
     *
     * <p>대표 이미지 교체는 이미 저장된 새 이미지로 main_image_id 만 바꾸므로 실패해도 기존 이미지가 유지된다.
     * 이전 이미지 파일은 지우지 않는다. P1 에는 이미지 참조 추적·정리 작업이 없어, 지운 뒤 롤백되거나 다른 곳에서
     * 참조 중이면 깨진 이미지가 생기기 때문이다.
     */
    @Transactional
    public Product update(Long productId, UpdateProductCommand command) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId));
        if (!Objects.equals(product.getVersion(), command.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, CONFLICT_MESSAGE);
        }
        String name = command.name() == null
                ? product.getName() : ProductRegistrationService.requireName(command.name());
        String description = command.description() == null
                ? product.getDescription() : ProductRegistrationService.requireDescription(command.description());
        Long mainImageId = command.mainImageId() == null ? product.getMainImageId() : command.mainImageId();
        if (!mainImageId.equals(product.getMainImageId()) && !productImageRepository.existsById(mainImageId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, ProductRegistrationService.MAIN_IMAGE_NOT_FOUND);
        }
        product.changeBasicInfo(name, description, mainImageId);
        try {
            // 응답에 오른 version 을 담고, 동시 수정 충돌을 커밋 전에 여기서 잡기 위해 바로 flush 한다.
            productRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.CONFLICT, CONFLICT_MESSAGE, e);
        }
        return product;
    }
}
