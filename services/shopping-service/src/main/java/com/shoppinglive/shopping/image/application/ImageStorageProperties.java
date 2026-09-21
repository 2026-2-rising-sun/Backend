package com.shoppinglive.shopping.image.application;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 상품 이미지 저장·검증 설정 ({@code shopping.image.*}).
 *
 * @param allowedTypes 허용 Content-Type. 검증기가 판별하는 JPEG·PNG 안에서만 좁힐 수 있다
 * @param maxWidth     허용 최대 가로 픽셀. 작은 파일이 거대한 비트맵으로 풀리는 압축 폭탄을 막는다
 */
@ConfigurationProperties("shopping.image")
public record ImageStorageProperties(
        @DefaultValue Storage storage,
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue({"image/jpeg", "image/png"}) List<String> allowedTypes,
        @DefaultValue("8000") int maxWidth,
        @DefaultValue("8000") int maxHeight) {

    /** 사용자 안내 문구용 용량 표기 (예: 5MB). */
    public String maxSizeLabel() {
        return maxSize.toMegabytes() > 0 ? maxSize.toMegabytes() + "MB" : maxSize.toKilobytes() + "KB";
    }

    /**
     * @param type 저장소 구현 선택. 지금은 {@code local} 뿐이고, S3 는 구현체를 추가한 뒤 이 값만 바꾼다
     * @param dir  로컬 저장 폴더. 기본값은 레포 밖인 {@code ~/.shoppinglive/shopping-images} 라 어느 폴더에서
     *             실행해도 업로드 이미지가 git 작업 트리에 생기지 않는다. 컨테이너에서는 볼륨이 마운트된 경로여야
     *             재기동에도 파일이 남는다
     */
    public record Storage(
            @DefaultValue("local") String type,
            Path dir) {

        public Storage {
            if (dir == null) {
                dir = Path.of(System.getProperty("user.home"), ".shoppinglive", "shopping-images");
            }
        }
    }
}
