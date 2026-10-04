package gov.itms.iam.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ext.javatime.deser.InstantDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

/**
 * Request-body hardening for every iam-service endpoint, in one place (SEC-022, CH-053). A request that breaks one of
 * these rules fails JSON binding and is answered 400 {@code VALIDATION_FAILED} by the platform problem handler,
 * instead of reaching the domain or the database. No value is silently coerced into something the caller did not send:
 * <ul>
 *   <li>an enum value in a request body (for example an access review {@code decision}) must be exactly its
 *       name as a JSON string; a number, padding or any other character is refused (query parameters:
 *       {@link StrictEnumParameters});</li>
 *   <li>an integer (for example an approval limit's {@code maxAmountMinor}, CC-003) must be a JSON integer; a
 *       fraction, an exponent form or a string is refused, never truncated or parsed;</li>
 *   <li>a text field must be a JSON string (a number or boolean is refused) and may not contain the NUL character
 *       (U+0000), which PostgreSQL cannot store;</li>
 *   <li>a date-time must be an RFC 3339 string (an epoch number is refused) within the four-digit-year range that the
 *       contract promises ({@value #EARLIEST_TEXT} to {@value #LATEST_TEXT}, microsecond precision as stored). This is a
 *       format and storage bound, not a business horizon.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class JsonInputHardening {

    static final String EARLIEST_TEXT = "0001-01-01T00:00:00Z";
    static final String LATEST_TEXT = "9999-12-31T23:59:59.999999Z";
    static final Instant EARLIEST = Instant.parse(EARLIEST_TEXT);
    static final Instant LATEST = Instant.parse(LATEST_TEXT);

    @Bean
    JsonMapperBuilderCustomizer iamJsonInputHardening() {
        SimpleModule module = new SimpleModule("iam-json-input-hardening");
        module.addDeserializer(String.class, new StrictStringDeserializer());
        module.addDeserializer(Instant.class, new BoundedInstantDeserializer(InstantDeserializer.INSTANT));
        module.setDeserializerModifier(new ValueDeserializerModifier() {
            @Override
            public ValueDeserializer<?> modifyEnumDeserializer(DeserializationConfig config, JavaType type,
                                                               BeanDescription.Supplier beanDescRef,
                                                               ValueDeserializer<?> deserializer) {
                return new StrictEnumDeserializer(type.getRawClass());
            }
        });
        return builder -> builder
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(LogicalType.Integer, cfg -> cfg
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .addModule(module);
    }

    static Map<String, Enum<?>> constantsByName(Class<?> enumType) {
        Map<String, Enum<?>> byName = new HashMap<>();
        for (Object constant : enumType.getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            byName.put(value.name(), value);
        }
        return Map.copyOf(byName);
    }

    /** Exact-name enum binding for request bodies: a JSON string equal to a constant's name, nothing else. */
    static final class StrictEnumDeserializer extends StdDeserializer<Object> {

        private final Map<String, Enum<?>> byName;

        StrictEnumDeserializer(Class<?> enumType) {
            super(enumType);
            this.byName = constantsByName(enumType);
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return ctxt.handleUnexpectedToken(handledType(), p);
            }
            String text = p.getString();
            Enum<?> value = byName.get(text);
            if (value == null) {
                throw ctxt.weirdStringException(text, handledType(), "not one of the values " + byName.keySet());
            }
            return value;
        }

        @Override
        public LogicalType logicalType() {
            return LogicalType.Enum;
        }

        @Override
        public boolean isCachable() {
            return true;
        }
    }

    /** Standard string binding, refusing non-string scalars and the NUL character. */
    static final class StrictStringDeserializer extends StdDeserializer<String> {

        StrictStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return (String) ctxt.handleUnexpectedToken(String.class, p);
            }
            String value = StringDeserializer.instance.deserialize(p, ctxt);
            if (value != null && value.indexOf('\u0000') >= 0) {
                throw ctxt.weirdStringException("", String.class, "text must not contain the NUL character");
            }
            return value;
        }

        @Override
        public LogicalType logicalType() {
            return LogicalType.Textual;
        }

        @Override
        public boolean isCachable() {
            return true;
        }
    }

    /** Standard {@link Instant} binding from an RFC 3339 string only, refusing instants outside the storage range. */
    static final class BoundedInstantDeserializer extends StdDeserializer<Instant> {

        private final ValueDeserializer<?> delegate;

        BoundedInstantDeserializer(ValueDeserializer<?> delegate) {
            super(Instant.class);
            this.delegate = delegate;
        }

        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return (Instant) ctxt.handleUnexpectedToken(Instant.class, p);
            }
            Instant value = (Instant) delegate.deserialize(p, ctxt);
            if (value != null && (value.isBefore(EARLIEST) || value.isAfter(LATEST))) {
                throw ctxt.weirdStringException(p.getString(), Instant.class,
                        "date-time must be between " + EARLIEST_TEXT + " and " + LATEST_TEXT);
            }
            return value;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            ValueDeserializer<?> contextual = delegate.createContextual(ctxt, property);
            return contextual == delegate ? this : new BoundedInstantDeserializer(contextual);
        }

        @Override
        public LogicalType logicalType() {
            return LogicalType.DateTime;
        }

        @Override
        public boolean isCachable() {
            return true;
        }
    }
}
