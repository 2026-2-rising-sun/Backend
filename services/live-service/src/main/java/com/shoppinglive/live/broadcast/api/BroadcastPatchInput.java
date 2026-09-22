package com.shoppinglive.live.broadcast.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * 생략된 필드는 null(=기존 값 유지), 본문에 명시적으로 null 을 준 필드는
 * Optional.empty() 로 역직렬화되어 400 으로 거절된다.
 */
@JsonDeserialize(using = BroadcastPatchInput.Deserializer.class)
public record BroadcastPatchInput(
    Optional<@Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.") String> title,

    Optional<Instant> scheduledAt,

    Optional<@Size(min = 1, max = 255, message = "채널 ARN은 최대 255자입니다.") String> channelArn,

    Optional<@Size(min = 1, max = 2048, message = "재생 URL은 최대 2048자입니다.") String> playbackUrl
) {
    /** record 생성자의 Optional 기본 역직렬화는 생략과 null을 구분하지 못한다. */
    public static final class Deserializer extends JsonDeserializer<BroadcastPatchInput> {
        @Override
        public BroadcastPatchInput deserialize(final JsonParser parser,
                                                final DeserializationContext context)
            throws IOException {
            final JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject()) {
                return (BroadcastPatchInput) context.handleUnexpectedToken(
                    BroadcastPatchInput.class, parser);
            }
            return new BroadcastPatchInput(
                field(parser, node, "title", String.class),
                field(parser, node, "scheduledAt", Instant.class),
                field(parser, node, "channelArn", String.class),
                field(parser, node, "playbackUrl", String.class));
        }

        private static <T> Optional<T> field(final JsonParser parser, final JsonNode object,
                                             final String name, final Class<T> type)
            throws IOException {
            final JsonNode value = object.get(name);
            if (value == null) {
                return null;
            }
            return value.isNull() ? Optional.empty()
                : Optional.ofNullable(parser.getCodec().treeToValue(value, type));
        }
    }
}
