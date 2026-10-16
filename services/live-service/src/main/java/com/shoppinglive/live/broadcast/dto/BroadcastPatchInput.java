package com.shoppinglive.live.broadcast.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** 생략된 필드는 null(=기존 값 유지), 본문에 명시적으로 null 을 준 필드는 Optional.empty() 로 역직렬화되어 400 으로 거절된다. */
@JsonDeserialize(using = BroadcastPatchInput.Deserializer.class)
public record BroadcastPatchInput(
        Optional<@Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.") String> title,
        Optional<Instant> scheduledAt,
        Optional<@Size(min = 1, max = 255, message = "채널 ARN은 최대 255자입니다.") String> channelArn,
        Optional<@Size(min = 1, max = 2048, message = "재생 URL은 최대 2048자입니다.") String> playbackUrl,
        Optional<@Size(max = 80) String> hostName,
        Optional<@Size(max = 2000) String> description,
        Optional<@Size(max = 36) String> thumbnailId) {
    public BroadcastPatchInput(
            Optional<String> title,
            Optional<Instant> scheduledAt,
            Optional<String> channelArn,
            Optional<String> playbackUrl) {
        this(title, scheduledAt, channelArn, playbackUrl, null, null, null);
    }

    /** record 생성자의 Optional 기본 역직렬화는 생략과 null을 구분하지 못한다. */
    public static final class Deserializer extends JsonDeserializer<BroadcastPatchInput> {
        private static final Set<String> FIELDS =
                Set.of(
                        "title",
                        "scheduledAt",
                        "channelArn",
                        "playbackUrl",
                        "hostName",
                        "description",
                        "thumbnailId");

        @Override
        public BroadcastPatchInput deserialize(
                final JsonParser parser, final DeserializationContext context) throws IOException {
            final JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject()) {
                return (BroadcastPatchInput)
                        context.handleUnexpectedToken(BroadcastPatchInput.class, parser);
            }
            // 트리 역직렬화에서도 계약과 동일하게 알 수 없는 필드를 거부한다.
            for (var names = node.fieldNames(); names.hasNext(); ) {
                if (!FIELDS.contains(names.next())) {
                    throw JsonMappingException.from(parser, "허용되지 않은 방송 수정 필드입니다.");
                }
            }
            return new BroadcastPatchInput(
                    field(parser, node, "title", String.class),
                    field(parser, node, "scheduledAt", Instant.class),
                    field(parser, node, "channelArn", String.class),
                    field(parser, node, "playbackUrl", String.class),
                    field(parser, node, "hostName", String.class),
                    field(parser, node, "description", String.class),
                    field(parser, node, "thumbnailId", String.class));
        }

        private static <T> Optional<T> field(
                final JsonParser parser,
                final JsonNode object,
                final String name,
                final Class<T> type)
                throws IOException {
            final JsonNode value = object.get(name);
            if (value == null) {
                return null;
            }
            // 문자열 필드는 모두 JSON 문자열이다. 숫자·불리언·epoch 시각을 문자열로 자동 변환하지 않는다.
            if (!value.isNull() && !value.isTextual()) {
                throw JsonMappingException.from(parser, name + "는 문자열이어야 합니다.");
            }
            return value.isNull()
                    ? Optional.empty()
                    : Optional.ofNullable(parser.getCodec().treeToValue(value, type));
        }
    }
}
