package com.shoppinglive.shopping.image.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.domain.ImageFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 업로드 파일이 정말 허용된 이미지인지 확인한다.
 *
 * <p>확장자·Content-Type 은 클라이언트가 마음대로 붙일 수 있으므로 시그니처로 형식을 판별하고 실제로 디코딩까지
 * 해 본다. 확장자·Content-Type 은 판별 결과와 어긋날 때 거절하는 용도로만 쓴다. 거절은 모두 400 과 구체적인
 * 사유로 알려 사용자가 파일을 고쳐 다시 올릴 수 있게 한다.
 */
@Component
public class ImageValidator {

    private static final String UNSUPPORTED_TYPE = "허용되지 않는 이미지 형식입니다 (JPEG, PNG 만 가능)";
    private static final String CORRUPTED = "손상되었거나 이미지로 해석할 수 없는 파일입니다";
    /** 전체 디코딩 때 메모리에 올릴 한 변의 최대 픽셀. 더 크면 건너뛰며 읽는다. */
    private static final int DECODE_TARGET_EDGE = 1024;

    private final ImageStorageProperties properties;

    public ImageValidator(ImageStorageProperties properties) {
        this.properties = properties;
    }

    public ValidatedImage validate(byte[] content, String declaredContentType, String originalFilename) {
        if (content == null || content.length == 0) {
            throw invalid("빈 파일입니다");
        }
        if (content.length > properties.maxSize().toBytes()) {
            throw invalid("이미지 용량이 " + properties.maxSizeLabel() + " 를 초과합니다");
        }
        ImageFormat format = ImageFormat.detect(content)
                .filter(detected -> properties.allowedTypes().contains(detected.contentType()))
                .orElseThrow(() -> invalid(UNSUPPORTED_TYPE));
        if (!contentTypeMatches(format, declaredContentType)) {
            throw invalid("파일 형식(Content-Type)이 실제 이미지 형식과 일치하지 않습니다");
        }
        String extension = StringUtils.getFilenameExtension(
                StringUtils.getFilename(StringUtils.cleanPath(originalFilename == null ? "" : originalFilename)));
        if (StringUtils.hasText(extension) && !format.acceptsExtension(extension.toLowerCase(Locale.ROOT))) {
            throw invalid("파일 확장자가 실제 이미지 형식과 일치하지 않습니다");
        }
        return decode(format, content);
    }

    /** 값이 없거나 형식을 모르는 클라이언트(curl 등)가 보내는 application/octet-stream 은 "미지정"으로 본다. */
    private static boolean contentTypeMatches(ImageFormat format, String declaredContentType) {
        if (!StringUtils.hasText(declaredContentType)) {
            return true;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(declaredContentType);
            return MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(mediaType)
                    || format.acceptsContentType(
                            (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /**
     * 헤더의 가로·세로를 먼저 읽어 한도를 넘으면 픽셀을 풀기 전에 거절하고(압축 폭탄 방어), 통과하면 끝까지
     * 디코딩해 잘리거나 깨진 파일을 걸러 낸다. 큰 이미지는 압축 데이터는 끝까지 풀되 건너뛴 픽셀만 메모리에
     * 올려, 8000x8000 비트맵(약 200MB)을 요청마다 만들지 않는다.
     */
    private ValidatedImage decode(ImageFormat format, byte[] content) {
        ImageReader reader = ImageIO.getImageReadersByFormatName(format.extension()).next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw invalid(CORRUPTED);
            }
            if (width > properties.maxWidth() || height > properties.maxHeight()) {
                throw invalid("이미지 크기가 최대 " + properties.maxWidth() + "x" + properties.maxHeight()
                        + " 픽셀을 초과합니다");
            }
            // JPEG reader 는 잘린 파일을 예외 없이 회색으로 채워 읽고 경고만 남긴다. 경고도 손상으로 본다.
            List<String> warnings = new ArrayList<>();
            reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, (Math.max(width, height) + DECODE_TARGET_EDGE - 1) / DECODE_TARGET_EDGE);
            param.setSourceSubsampling(step, step, 0, 0);
            reader.read(0, param);
            if (!warnings.isEmpty()) {
                throw invalid(CORRUPTED);
            }
            return new ValidatedImage(format, content.length, width, height);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // 디코더는 손상 파일에 IIOException 외에도 IndexOutOfBounds 등 런타임 예외를 던진다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST, CORRUPTED, e);
        } finally {
            reader.dispose();
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
