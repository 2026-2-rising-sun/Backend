package com.shoppinglive.shopping.image.application;

/**
 * 이미지 id 로 클라이언트가 받을 URL 을 만든다. DB 에 URL 을 저장하지 않고 조회 시점에 만들어, S3·CDN 으로
 * 옮길 때 이 구현만 바꾸면 되게 한다. 파일 시스템 경로는 절대 드러내지 않는다.
 */
public interface ImageUrlResolver {

    String urlOf(Long imageId);
}
