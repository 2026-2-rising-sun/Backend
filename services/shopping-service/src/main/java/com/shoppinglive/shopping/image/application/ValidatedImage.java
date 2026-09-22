package com.shoppinglive.shopping.image.application;

import com.shoppinglive.shopping.image.domain.ImageFormat;

/** 검증을 통과한 이미지. 클라이언트가 보낸 값이 아니라 파일에서 읽은 형식·크기다. */
public record ValidatedImage(ImageFormat format, long sizeBytes, int width, int height) {
}
