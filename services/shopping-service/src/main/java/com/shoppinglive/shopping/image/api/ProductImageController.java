package com.shoppinglive.shopping.image.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.image.application.ImageFile;
import com.shoppinglive.shopping.image.application.ImageStorageException;
import com.shoppinglive.shopping.image.application.ProductImageService;
import com.shoppinglive.shopping.image.application.UploadedImage;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 상품 이미지 API. 업로드는 상품 등록·수정 화면에서만 쓰므로 관리용 경로(/v1/admin/**)에 두어
 * 관리자 인증이 붙을 때 함께 보호되게 한다. 조회는 공개 목록·상세·방송 화면이 쓰므로 공개 경로에 둔다.
 */
@RestController
public class ProductImageController {

    private final ProductImageService productImageService;

    public ProductImageController(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    @PostMapping(path = "/v1/admin/product-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductImageUploadResponse>> upload(@RequestPart("file") MultipartFile file) {
        UploadedImage uploaded = productImageService.upload(readBytes(file), file.getContentType(),
                file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ProductImageUploadResponse.from(uploaded)));
    }

    /**
     * 경로 문자열이 아닌 숫자 id 로만 찾으므로 폴더 탐색·목록 노출이 불가능하다. 이미지를 바꾸면 항상 새 id(새 URL)가
     * 생기고 같은 id 의 내용은 바뀌지 않으므로 1년 immutable 캐시가 안전하다. nosniff 로 브라우저가 내용을 보고
     * 다른 형식(HTML 등)으로 해석하지 못하게 한다.
     */
    @GetMapping("/v1/product-images/{imageId}")
    public ResponseEntity<Resource> get(@PathVariable Long imageId) {
        ImageFile file = productImageService.getFile(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    /** multipart 임시 파일을 못 읽는 건 사용자 파일이 아니라 서버 문제라 저장 실패(500)로 알린다. */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ImageStorageException("업로드된 multipart 파일 읽기 실패", e);
        }
    }
}
