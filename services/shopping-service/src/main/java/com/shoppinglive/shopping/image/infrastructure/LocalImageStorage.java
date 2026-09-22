package com.shoppinglive.shopping.image.infrastructure;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageStorage;
import com.shoppinglive.shopping.image.application.ImageStorageException;
import com.shoppinglive.shopping.image.application.ImageStorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 로컬 폴더 저장소 (S3 도입 전 대체 구현). 폴더에는 서버가 만든 키 이름의 파일만 평평하게 둔다.
 * 재기동해도 파일을 지우지 않으며, 컨테이너에서는 이 폴더에 볼륨을 붙여야 파일이 남는다.
 */
@Component
@ConditionalOnProperty(name = "shopping.image.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalImageStorage.class);

    private final Path root;

    /**
     * 기동 시 폴더를 만들고 실제로 파일을 써 본다. 권한·볼륨이 잘못된 채 떠서 업로드마다 실패하기보다
     * 기동을 멈추고 원인을 바로 드러낸다.
     */
    public LocalImageStorage(ImageStorageProperties properties) {
        this.root = properties.storage().dir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.delete(Files.createTempFile(root, ".write-check-", ".tmp"));
        } catch (IOException e) {
            log.error("이미지 저장 폴더를 쓸 수 없습니다: {} (SHOPPING_IMAGE_DIR·볼륨 권한 확인)", root, e);
            throw new IllegalStateException("이미지 저장 폴더를 쓸 수 없습니다: " + root, e);
        }
        log.info("이미지 저장 폴더: {}", root);
    }

    /** 같은 폴더의 임시 파일에 끝까지 쓴 뒤 원자적으로 옮겨, 실패해도 키 이름의 반쪽 파일이 남지 않게 한다. */
    @Override
    public void store(String key, InputStream content, long size, String contentType) {
        Path target = resolve(key);
        if (Files.exists(target)) {
            throw new ImageStorageException("이미 같은 키의 파일이 있습니다: " + key, null);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(root, ".upload-", ".tmp");
            long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            if (written != size) {
                throw new ImageStorageException(
                        "저장 크기 불일치: key=" + key + ", expected=" + size + ", written=" + written, null);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
        } catch (IOException e) {
            throw new ImageStorageException("이미지 파일 저장 실패: key=" + key, e);
        } finally {
            if (temp != null) {
                deleteTempQuietly(temp);
            }
        }
    }

    @Override
    public Resource load(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이미지를 찾을 수 없습니다");
        }
        return new PathResource(target);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new ImageStorageException("이미지 파일 삭제 실패: key=" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /** 키가 실수로라도 폴더 밖({@code ../x}, 절대 경로)이나 하위 폴더를 가리키지 못하게 루트 바로 아래만 허용한다. */
    private Path resolve(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("저장 키가 비어 있습니다");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || !root.equals(target.getParent())) {
            throw new IllegalArgumentException("저장 폴더를 벗어나는 키입니다: " + key);
        }
        return target;
    }

    private static void deleteTempQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("임시 파일 삭제 실패: {}", temp, e);
        }
    }
}
