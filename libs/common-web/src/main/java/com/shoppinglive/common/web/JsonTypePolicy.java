package com.shoppinglive.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.AccessPattern;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** JSON body types follow the API schema; MVC path/query conversion remains unchanged. */
final class JsonTypePolicy implements Jackson2ObjectMapperBuilderCustomizer {
    @Override
    public void customize(Jackson2ObjectMapperBuilder builder) {
        // Preserve fractional precision when a PATCH first reads JsonNode or an untyped Map.
        builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        builder.postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        });
        // JSON Schema integer accepts 1.0 and 1e0, but never truncates a fractional value.
        for (Class<?> type : new Class<?>[] {Long.class, long.class, Integer.class, int.class,
                Short.class, short.class, Byte.class, byte.class, BigInteger.class}) {
            builder.deserializerByType(type, new ExactIntegerDeserializer(type));
        }
        // JavaTime's default Instant deserializer accepts epoch numbers independently of coercion settings.
        builder.deserializerByType(Instant.class, new IsoInstantDeserializer());
    }

    private static final class ExactIntegerDeserializer extends StdScalarDeserializer<Number> {
        private final JsonDeserializer<?> delegate;

        private ExactIntegerDeserializer(Class<?> type) {
            super(type);
            delegate = NumberDeserializers.find(type, type.getName());
        }

        @Override
        public Number deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return (Number) delegate.deserialize(parser, context);
            }
            try {
                BigDecimal decimal = parser.getDecimalValue();
                parser.streamReadConstraints().validateBigIntegerScale(decimal.scale());
                BigInteger exact = decimal.toBigIntegerExact();
                Class<?> type = handledType();
                if (type == Long.class || type == long.class) return exact.longValueExact();
                if (type == Integer.class || type == int.class) return exact.intValueExact();
                if (type == Short.class || type == short.class) return exact.shortValueExact();
                if (type == Byte.class || type == byte.class) return exact.byteValueExact();
                return exact;
            } catch (ArithmeticException | NumberFormatException exception) {
                return context.reportInputMismatch(handledType(), "Expected an integer within the target type's range");
            }
        }

        @Override public LogicalType logicalType() { return LogicalType.Integer; }
        @Override public Number getNullValue(DeserializationContext context) throws JsonMappingException {
            return (Number) delegate.getNullValue(context);
        }
        @Override public AccessPattern getNullAccessPattern() { return delegate.getNullAccessPattern(); }
    }

    private static final class IsoInstantDeserializer extends StdScalarDeserializer<Instant> {
        private IsoInstantDeserializer() { super(Instant.class); }

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(Instant.class, "Expected an ISO-8601 date-time string");
            }
            try {
                return Instant.parse(parser.getText());
            } catch (DateTimeParseException exception) {
                return context.reportInputMismatch(Instant.class, "Expected an ISO-8601 date-time string");
            }
        }
    }
}
