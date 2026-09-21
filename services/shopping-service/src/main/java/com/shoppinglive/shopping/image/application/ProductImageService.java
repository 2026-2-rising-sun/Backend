package com.shoppinglive.shopping.image.application;

import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private static final int ORIGINAL_FILENAME_MAX_LENGTH = 255;

    private final ImageValidator validator;
    private final ImageStorage storage;
    private final ProductImageRepository repository;

    public ProductImageService(ImageValidator validator, ImageStorage storage, ProductImageRepository repository) {
        this.validator = validator;
        this.storage = storage;
        this.repository = repository;
    }

    /**
     * 검증 → 파일 저장 → 행 저장. 행 저장이 실패하면 방금 쓴 파일을 지워 행 없는 파일을 남기지 않는다.
     * 메서드에 트랜잭션을 걸면 커밋이 반환 뒤에 일어나 그 실패에서 파일을 지울 수 없으므로,
     * {@code saveAndFlush} 가 자체 트랜잭션으로 바로 커밋하게 둔다.
     */
    public UploadedImage upload(byte[] content, String declaredContentType, String originalFilename) {
        ValidatedImage image = validator.validate(content, declaredContentType, originalFilename);
        // 같은 원본 파일명의 다른 이미지가 서로 덮어쓰지 않도록 키는 서버가 만든다.
        String storageKey = UUID.randomUUID() + "." + image.format().extension();
        storage.store(storageKey, new ByteArrayInputStream(content), content.length, image.format().contentType());
        try {
            return UploadedImage.from(repository.saveAndFlush(new ProductImage(storageKey, image.format(),
                    image.sizeBytes(), image.width(), image.height(), displayFilename(originalFilename))));
        } catch (RuntimeException e) {
            try {
                storage.delete(storageKey);
            } catch (RuntimeException deleteFailure) {
                e.addSuppressed(deleteFailure);
                log.error("행 저장 실패 후 이미지 파일 삭제도 실패. 수동 정리 필요: key={}", storageKey, deleteFailure);
            }
            throw e;
        }
    }

    /** 브라우저에 따라 전체 경로가 오기도 하므로 파일명만 남기고 컬럼 길이에 맞춘다. */
    private static String displayFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return null;
        }
        // 제어 문자(NUL 등)는 PostgreSQL 저장을 실패시키므로 지우고, 서로게이트 쌍이 잘리지 않게 코드포인트로 자른다.
        String filename = StringUtils.getFilename(StringUtils.cleanPath(originalFilename))
                .replaceAll("\\p{Cntrl}", "").strip();
        if (filename.isEmpty()) {
            return null;
        }
        int codePoints = filename.codePointCount(0, filename.length());
        return codePoints > ORIGINAL_FILENAME_MAX_LENGTH
                ? filename.substring(filename.offsetByCodePoints(0, codePoints - ORIGINAL_FILENAME_MAX_LENGTH))
                : filename;
    }
}
