package com.shoppinglive.shopping.image.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** 업로드를 허용하는 이미지 형식. 확장자·Content-Type 이 아니라 파일 앞부분 시그니처로 판별한다. */
public enum ImageFormat {

    JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            Set.of("jpg", "jpeg"),
            // image/jpg·image/pjpeg 는 비표준이지만 일부 클라이언트가 실제로 보낸다.
            Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
    PNG("image/png", "png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
            Set.of("png"),
            Set.of("image/png"));

    private final String contentType;
    private final String extension;
    private final byte[] signature;
    private final Set<String> acceptedExtensions;
    private final Set<String> acceptedContentTypes;

    ImageFormat(String contentType, String extension, byte[] signature, Set<String> acceptedExtensions,
            Set<String> acceptedContentTypes) {
        this.contentType = contentType;
        this.extension = extension;
        this.signature = signature;
        this.acceptedExtensions = acceptedExtensions;
        this.acceptedContentTypes = acceptedContentTypes;
    }

    public static Optional<ImageFormat> detect(byte[] content) {
        return Arrays.stream(values())
                .filter(format -> content.length >= format.signature.length
                        && Arrays.equals(content, 0, format.signature.length, format.signature, 0,
                        format.signature.length))
                .findFirst();
    }

    public String contentType() {
        return contentType;
    }

    /** 저장 키에 붙이는 확장자. ImageIO reader 이름으로도 쓴다. */
    public String extension() {
        return extension;
    }

    public boolean acceptsExtension(String extension) {
        return acceptedExtensions.contains(extension);
    }

    public boolean acceptsContentType(String contentType) {
        return acceptedContentTypes.contains(contentType);
    }
}
