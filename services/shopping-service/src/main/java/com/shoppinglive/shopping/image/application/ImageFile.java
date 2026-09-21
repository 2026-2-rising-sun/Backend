package com.shoppinglive.shopping.image.application;

import org.springframework.core.io.Resource;

/** 내려보낼 이미지 파일. Content-Type·크기는 업로드 때 검증해 저장한 값이다. */
public record ImageFile(String contentType, long sizeBytes, Resource content) {
}
