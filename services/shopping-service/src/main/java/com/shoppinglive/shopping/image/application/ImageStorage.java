package com.shoppinglive.shopping.image.application;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * 이미지 파일 저장소 포트. 유스케이스는 파일이 로컬 폴더에 있는지 S3 에 있는지 모르고 서버가 만든 키로만 다룬다.
 * 구현체는 {@code shopping.image.storage.type} 으로 하나만 선택되며, 입출력 실패는
 * {@link ImageStorageException} 으로 알린다. 저장이 끝나지 않았는데 정상 반환하지 않는다.
 */
public interface ImageStorage {

    /** 같은 키가 이미 있으면 덮어쓰지 않고 실패한다. {@code size} 와 실제로 쓴 크기가 다르면 실패로 본다. */
    void store(String key, InputStream content, long size, String contentType);

    /** 키에 해당하는 파일이 없으면 {@code BusinessException(NOT_FOUND)}. */
    Resource load(String key);

    /** 이미 없으면 아무 일도 하지 않는다. */
    void delete(String key);

    boolean exists(String key);
}
