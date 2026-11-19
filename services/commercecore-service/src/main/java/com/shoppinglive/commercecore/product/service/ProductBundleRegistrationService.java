package com.shoppinglive.commercecore.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.product.dto.ProductBundleResponse.RegisteredSku;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest.InitialSku;
import com.shoppinglive.commercecore.product.entity.*;
import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.commercecore.product.repository.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.commercecore.storage.*;
import com.shoppinglive.common.core.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProductBundleRegistrationService {
    private static final Logger log =
            LoggerFactory.getLogger(ProductBundleRegistrationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ProductInfoEventPublisher events;
    private final SkuService skus;
    private final ImageValidator validator;
    private final ImageStorage storage;
    private final ImageUrlResolver urls;
    private final ObjectMapper mapper;

    public ProductBundleRegistrationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ProductRepository products,
            ProductImageRepository images,
            ProductInfoEventPublisher events,
            SkuService skus,
            ImageValidator validator,
            ImageStorage storage,
            ImageUrlResolver urls,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.products = products;
        this.images = images;
        this.events = events;
        this.skus = skus;
        this.validator = validator;
        this.storage = storage;
        this.urls = urls;
        this.mapper = mapper;
    }

    public ProductBundleResponse register(
            String seller,
            String key,
            RegisterProductBundleRequest input,
            byte[] content,
            String contentType,
            String filename) {
        if (seller == null
                || seller.isBlank()
                || seller.length() > 36
                || key == null
                || key.isBlank()
                || key.length() > 64) {
            throw invalid("판매자와 X-Idempotency-Key(1~64자)가 필요합니다.");
        }
        var request = normalize(input);
        var image = validator.validate(content, contentType, filename);
        String fingerprint = fingerprint(request, content, filename);
        // 파일을 쓰기 전에 복구 위치를 별도 커밋한다. 요청 행 잠금은 저장·DB 커밋·정리와 재시도를 직렬화한다.
        transactions.executeWithoutResult(
                status ->
                        jdbc.update(
                                """
                INSERT INTO product_registration(seller_id,request_key,fingerprint,storage_key)
                VALUES(?,?,?,?) ON CONFLICT(seller_id,request_key) DO NOTHING
                """,
                                seller,
                                key,
                                fingerprint,
                                "registration-"
                                        + UUID.randomUUID()
                                        + "."
                                        + image.format().extension()));
        try {
            return transactions.execute(
                    status -> {
                        var row = locked(seller, key);
                        if (!row.fingerprint().equals(fingerprint))
                            throw new BusinessException(
                                    ErrorCode.CONFLICT, "같은 멱등키에 다른 등록 내용을 사용할 수 없습니다.");
                        if (row.response() != null) return decode(row.response());
                        if (!storage.exists(row.storageKey())) {
                            storage.store(
                                    row.storageKey(),
                                    new ByteArrayInputStream(content),
                                    content.length,
                                    image.format().contentType());
                        }
                        var savedImage =
                                images.saveAndFlush(
                                        new ProductImage(
                                                row.storageKey(),
                                                image.format(),
                                                image.sizeBytes(),
                                                image.width(),
                                                image.height(),
                                                displayFilename(filename)));
                        var product =
                                products.saveAndFlush(
                                        Product.registerForSale(
                                                request.name(),
                                                request.description(),
                                                savedImage.getId(),
                                                seller,
                                                request.category(),
                                                request.skus().size()));
                        var registered = new ArrayList<RegisteredSku>();
                        for (var sku : request.skus()) {
                            var created =
                                    skus.createOnSale(
                                            product.getId(),
                                            sku.price(),
                                            sku.initialStock(),
                                            product.getName(),
                                            sku.optionLabel());
                            registered.add(
                                    new RegisteredSku(
                                            created.skuId(),
                                            sku.optionLabel(),
                                            created.price(),
                                            created.available(),
                                            created.reserved(),
                                            created.status()));
                        }
                        events.publish(product);
                        var response =
                                new ProductBundleResponse(
                                        product.getId(),
                                        savedImage.getId(),
                                        urls.urlOf(savedImage.getId()),
                                        true,
                                        List.copyOf(registered));
                        jdbc.update(
                                """
                        UPDATE product_registration SET response_json=?,updated_at=CURRENT_TIMESTAMP
                        WHERE seller_id=? AND request_key=?
                        """,
                                encode(response),
                                seller,
                                key);
                        return response;
                    });
        } catch (RuntimeException failure) {
            // 커밋 결과가 불명확해도 완료 행을 확인하기 전에는 파일을 지우지 않는다.
            try {
                var committed =
                        transactions.execute(
                                status -> {
                                    var row = locked(seller, key);
                                    if (!row.fingerprint().equals(fingerprint)) return null;
                                    if (row.response() != null) return decode(row.response());
                                    deleteUnreferenced(row.storageKey());
                                    return null;
                                });
                if (committed != null) return committed;
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.warn("등록 실패 후 파일 정리 보류: 판매자={}, 키={}", seller, key, cleanupFailure);
            }
            throw failure;
        }
    }

    /** 중단된 요청은 1시간 뒤 정리한다. 활성 등록이 잠근 행은 건너뛰고 참조가 없는 전용 파일만 지운다. */
    @Scheduled(
            initialDelayString = "${commercecore.product.registration-cleanup-delay-ms:60000}",
            fixedDelayString = "${commercecore.product.registration-cleanup-delay-ms:60000}")
    public void cleanupAbandoned() {
        transactions.executeWithoutResult(
                status -> {
                    var rows =
                            jdbc.query(
                                    """
                    SELECT seller_id,request_key,storage_key FROM product_registration
                    WHERE response_json IS NULL AND updated_at < CURRENT_TIMESTAMP - INTERVAL '1 hour'
                    ORDER BY updated_at LIMIT 50 FOR UPDATE SKIP LOCKED
                    """,
                                    (rs, n) ->
                                            new String[] {
                                                rs.getString(1), rs.getString(2), rs.getString(3)
                                            });
                    for (var row : rows) {
                        try {
                            deleteUnreferenced(row[2]);
                            jdbc.update(
                                    "UPDATE product_registration SET updated_at=CURRENT_TIMESTAMP WHERE seller_id=? AND request_key=?",
                                    row[0],
                                    row[1]);
                        } catch (ImageStorageException failure) {
                            log.warn("미완료 등록 이미지 정리 재시도 예정: key={}", row[2], failure);
                        }
                    }
                });
    }

    private void deleteUnreferenced(String key) {
        // 메타데이터가 존재하면 다른 상품이 공유했을 가능성까지 보존한다.
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM product_image WHERE storage_key=?", Long.class, key)
                == 0) storage.delete(key);
    }

    private Registration locked(String seller, String key) {
        return jdbc.queryForObject(
                """
                SELECT fingerprint,storage_key,response_json FROM product_registration
                WHERE seller_id=? AND request_key=? FOR UPDATE
                """,
                (rs, n) -> new Registration(rs.getString(1), rs.getString(2), rs.getString(3)),
                seller,
                key);
    }

    private RegisterProductBundleRequest normalize(RegisterProductBundleRequest request) {
        if (request == null
                || request.skus() == null
                || request.skus().isEmpty()
                || request.skus().size() > 100) throw invalid("SKU는 1~100개가 필요합니다.");
        var labels = new HashSet<String>();
        var normalized = new ArrayList<InitialSku>();
        for (var sku : request.skus()) {
            if (sku == null
                    || sku.price() == null
                    || sku.price() <= 0
                    || sku.initialStock() == null
                    || sku.initialStock() < 0) throw invalid("SKU 가격은 양수, 초기 재고는 0 이상이어야 합니다.");
            String label = sku.optionLabel() == null ? "" : sku.optionLabel().strip();
            if (label.length() > 255
                    || (request.skus().size() > 1 && label.isEmpty())
                    || !labels.add(label)) throw invalid("다중 SKU는 서로 다른 옵션 표시명(1~255자)이 필요합니다.");
            normalized.add(
                    new InitialSku(
                            label.isEmpty() ? null : label, sku.price(), sku.initialStock()));
        }
        return new RegisterProductBundleRequest(
                ProductRegistrationService.requireName(request.name()),
                ProductRegistrationService.requireDescription(request.description()),
                request.category(),
                List.copyOf(normalized));
    }

    private String fingerprint(
            RegisterProductBundleRequest request, byte[] content, String filename) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(encode(request).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(
                    Objects.toString(displayFilename(filename), "")
                            .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("등록 결과 직렬화 실패", failure);
        }
    }

    private ProductBundleResponse decode(String value) {
        try {
            return mapper.readValue(value, ProductBundleResponse.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("등록 결과 복원 실패", failure);
        }
    }

    private static String displayFilename(String value) {
        if (value == null) return null;
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        return name.substring(
                0,
                name.offsetByCodePoints(0, Math.min(255, name.codePointCount(0, name.length()))));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private record Registration(String fingerprint, String storageKey, String response) {}
}
