package com.shoppinglive.shopping.image.application;

import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 로컬 개발용 이미지 초기화. 평소 재기동은 파일을 절대 지우지 않으므로, 쌓인 테스트 업로드를 비울 때만
 * 개발자가 명시적으로 호출한다. local 프로필이 아니면 빈이 만들어지지 않는다.
 */
@Service
@Profile("local")
public class DevImageResetService {

    private static final Logger log = LoggerFactory.getLogger(DevImageResetService.class);

    private final ProductImageRepository repository;
    private final ImageStorage storage;

    public DevImageResetService(ProductImageRepository repository, ImageStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /**
     * 상품에 연결되지 않은 이미지의 행을 먼저 지우고 파일을 지운다. 반대로 하다 행 삭제가 실패하면 파일 없는
     * 행이 남는다. 파일 삭제 실패는 행이 이미 없어 해가 없으므로 경고만 남긴다.
     */
    public int deleteUnreferencedImages() {
        List<ProductImage> targets = repository.findAllNotReferencedByProduct();
        if (targets.isEmpty()) {
            return 0;
        }
        repository.deleteAllByIdInBatch(targets.stream().map(ProductImage::getId).toList());
        for (ProductImage image : targets) {
            try {
                storage.delete(image.getStorageKey());
            } catch (RuntimeException e) {
                log.warn("개발용 초기화 중 이미지 파일 삭제 실패: key={}", image.getStorageKey(), e);
            }
        }
        log.info("개발용 초기화: 상품에 연결되지 않은 이미지 {}건 삭제", targets.size());
        return targets.size();
    }
}
