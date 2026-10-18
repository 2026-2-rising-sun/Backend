package com.shoppinglive.live.thumbnail;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BroadcastThumbnailService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final BroadcastThumbnailRepository repository;

    public BroadcastThumbnailService(BroadcastThumbnailRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public BroadcastThumbnail upload(byte[] content, String declaredType) {
        String type = validate(content, declaredType);
        return repository.save(new BroadcastThumbnail(sellerId(), type, content));
    }

    public static String validate(byte[] content, String declaredType) {
        if (content.length == 0 || content.length > MAX_BYTES)
            throw invalid("썸네일은 2MB 이하의 PNG·JPEG 이미지여야 합니다.");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("읽을 수 없는 이미지입니다.");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                String type =
                        "png".equalsIgnoreCase(format)
                                ? "image/png"
                                : "jpeg".equalsIgnoreCase(format) ? "image/jpeg" : null;
                if (type == null || !type.equalsIgnoreCase(declaredType))
                    throw invalid("실제 이미지 형식과 Content-Type이 일치해야 합니다.");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096)
                    throw invalid("썸네일은 최대 4096×4096 픽셀입니다.");
                var parameters = reader.getDefaultReadParam();
                int step = Math.max(1, (Math.max(width, height) + 1023) / 1024);
                parameters.setSourceSubsampling(step, step, 0, 0);
                reader.addIIOReadWarningListener(
                        (source, warning) -> {
                            throw invalid("손상된 이미지입니다.");
                        });
                reader.read(0, parameters);
                return type;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("손상된 이미지입니다.");
        }
    }

    @Transactional(readOnly = true)
    public BroadcastThumbnail get(String id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public void assertOwned(String id) {
        if (id == null || id.isEmpty()) return;
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw invalid("올바른 썸네일 ID가 필요합니다.");
        }
        if (!get(id).getSellerId().equals(sellerId()))
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private static String sellerId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return auth.getName();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
