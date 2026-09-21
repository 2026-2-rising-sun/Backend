package com.shoppinglive.shopping.image.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.image.application.ImageStorageException;
import com.shoppinglive.shopping.image.application.ProductImageService;
import com.shoppinglive.shopping.image.application.UploadedImage;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/product-images")
public class ProductImageController {

    private final ProductImageService productImageService;

    public ProductImageController(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductImageUploadResponse>> upload(@RequestPart("file") MultipartFile file) {
        UploadedImage uploaded = productImageService.upload(readBytes(file), file.getContentType(),
                file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ProductImageUploadResponse.from(uploaded)));
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
