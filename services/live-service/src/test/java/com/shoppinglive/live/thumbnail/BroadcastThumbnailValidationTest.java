package com.shoppinglive.live.thumbnail;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.common.core.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BroadcastThumbnailValidationTest {
    public static byte[] png(int width) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("실제 PNG만 허용하고 형식 위장·손상·빈 파일·용량·픽셀 초과를 거절한다")
    void validatesImageBeforeStoring() throws Exception {
        assertThat(BroadcastThumbnailService.validate(png(10), "image/png")).isEqualTo("image/png");
        assertThatThrownBy(() -> BroadcastThumbnailService.validate(png(10), "image/jpeg"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BroadcastThumbnailService.validate(new byte[0], "image/png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                BroadcastThumbnailService.validate(
                                        new byte[BroadcastThumbnailService.MAX_BYTES + 1],
                                        "image/png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BroadcastThumbnailService.validate(png(5000), "image/png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () -> BroadcastThumbnailService.validate("broken".getBytes(), "image/png"))
                .isInstanceOf(BusinessException.class);
    }
}
