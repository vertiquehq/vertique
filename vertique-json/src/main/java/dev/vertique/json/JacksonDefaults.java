// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Applies the broadly-safe {@code vertique} opinionated JSON defaults to an {@link ObjectMapper}.
 *
 * <p>The defaults applied by {@link #apply(ObjectMapper)} are:
 * <ol>
 *   <li>Unknown enum string → annotated {@code @JsonEnumDefaultValue} constant
 *       ({@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} enabled).</li>
 *   <li>{@code java.time} types → ISO-8601 strings with original offset/zone preserved
 *       ({@code JavaTimeModule} registered; {@code WRITE_DATES_AS_TIMESTAMPS} disabled;
 *       {@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE} disabled; {@code VertxModule} re-registered
 *       last so Vert.x's {@code Instant} serializer stays authoritative).</li>
 *   <li>Decimal JSON numbers → {@link java.math.BigDecimal}
 *       ({@code USE_BIG_DECIMAL_FOR_FLOATS} enabled).</li>
 *   <li>Null-valued fields omitted on serialization
 *       ({@code SerializationInclusion.NON_NULL}).</li>
 * </ol>
 *
 * <p>This helper does <strong>not</strong> alter {@link java.math.BigDecimal} serialization (stays
 * the Jackson-default JSON number) or {@link String} coercion (stays Jackson-default coercion), so a
 * mapper configured only by this class is fully symmetric. The opt-in serdes
 * ({@link BigDecimalAsStringSerializer}, {@link BigDecimalStrictStringDeserializer},
 * {@link StrictStringDeserializer}) are deliberately <strong>not</strong> registered here.
 *
 * <p>The only <strong>persistent</strong> changes this helper makes are the four defaults listed
 * above. {@code MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS} is toggled off transiently to
 * force the final {@code VertxModule} re-registration to win, then restored to the value it had on
 * entry — a caller who deliberately disabled that feature keeps it disabled.
 *
 * <p>The {@code vertique} built-in profile is exactly the output of
 * {@code JacksonDefaults.apply(new ObjectMapper())}.
 */
public final class JacksonDefaults {

    private JacksonDefaults() {}

    /**
     * Applies the broadly-safe {@code vertique} defaults to {@code mapper} and returns the same
     * instance (mutates and returns).
     *
     * <p>Applied in order:
     * <ol>
     *   <li>Register {@link JavaTimeModule} (ISO-8601 for the full {@code java.time} set).</li>
     *   <li>Disable {@code WRITE_DATES_AS_TIMESTAMPS} (emit ISO-8601 strings, not numeric arrays).</li>
     *   <li>Disable {@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE} (preserve the original offset/zone on
     *       deserialization; a deserialized {@code OffsetDateTime} keeps its {@code +02:00}, not UTC).</li>
     *   <li>Re-register {@link VertxJsonSupport#module()} <strong>last</strong> so that Vert.x's
     *       {@code Instant} serializer overrides the one from {@code JavaTimeModule} — keeping
     *       {@code Instant} output byte-identical to the {@code vertx} built-in profile (NFR-JSON-012).
     *       Because {@code MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS} is ON by default, the
     *       final registration is wrapped in a temporary toggle of that feature so it still takes effect
     *       even when a caller had already registered {@code VertxModule} on the mapper — otherwise the
     *       re-registration would be a silent no-op and jsr310's serializer would stay authoritative
     *       (FR-JSON-047). The toggle is scoped to this one registration and the feature is restored in a
     *       {@code finally} block to the value it had on entry — so a caller who deliberately disabled
     *       dedup keeps it disabled; this method's only persistent changes are the four documented
     *       defaults below.</li>
     *   <li>Enable {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} (unknown enum strings map to
     *       the {@code @JsonEnumDefaultValue}-annotated constant; enums without that annotation still
     *       reject unknown values).</li>
     *   <li>Enable {@code USE_BIG_DECIMAL_FOR_FLOATS} (decimal JSON numbers bind to
     *       {@link java.math.BigDecimal} instead of {@code double}, preserving precision).</li>
     *   <li>Set serialization inclusion to {@link JsonInclude.Include#NON_NULL} (null fields omitted).</li>
     * </ol>
     *
     * <p>This method does <strong>not</strong> change {@link java.math.BigDecimal} serialization (stays
     * a JSON number — stock Jackson behavior) or {@link String} coercion. Callers wishing string-form
     * {@code BigDecimal} or strict-string deserialization should register the opt-in serdes separately.
     *
     * @param mapper the {@link ObjectMapper} to configure; must not be {@code null}
     * @return the same {@code mapper} instance, now configured with the {@code vertique} defaults
     * @see BigDecimalAsStringSerializer
     * @see BigDecimalStrictStringDeserializer
     * @see StrictStringDeserializer
     */
    public static ObjectMapper apply(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        // Re-register VertxModule LAST so Vert.x's Instant/JsonObject/Buffer serializers remain
        // authoritative — they override JavaTimeModule's Instant serializer (NFR-JSON-012, FR-JSON-047).
        //
        // MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS is ON by default, so if a CALLER already
        // registered VertxJsonSupport.module() on this mapper before calling apply(), a plain
        // registerModule(VertxModule) here would be a silent no-op — leaving JavaTimeModule's jsr310
        // Instant serializer authoritative instead of Vert.x's. Temporarily disable dedup so this final
        // registration always re-applies Vert.x's serializers (making them win), then restore the
        // feature to the value it had on entry. The toggle is scoped to this single registration; a
        // caller who deliberately disabled dedup keeps it disabled (we never force it back to true).
        boolean origDedup = mapper.isEnabled(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS);
        try {
            mapper.configure(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS, false);
            mapper.registerModule(VertxJsonSupport.module());
        } finally {
            mapper.configure(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS, origDedup);
        }
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
