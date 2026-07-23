// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable holder for {@link CursorValueCodec} instances used by {@link PageCursor} for
 * token serialization and deserialization.
 *
 * <p>A default instance with all built-in codecs is available via {@link #defaults()}.
 * Custom codecs can be added via {@link #with(CursorValueCodec)}, which returns a new
 * immutable instance leaving the original unchanged.
 *
 * <h3>Built-in types</h3>
 * <table>
 *   <tr><th>Java type</th><th>Prefix</th></tr>
 *   <tr><td>{@link UUID}</td><td>{@code uuid}</td></tr>
 *   <tr><td>{@link Instant}</td><td>{@code instant}</td></tr>
 *   <tr><td>{@link OffsetDateTime}</td><td>{@code odt}</td></tr>
 *   <tr><td>{@link LocalDateTime}</td><td>{@code ldt}</td></tr>
 *   <tr><td>{@link LocalDate}</td><td>{@code date}</td></tr>
 *   <tr><td>{@link String}</td><td>{@code str}</td></tr>
 *   <tr><td>{@link Integer}</td><td>{@code int}</td></tr>
 *   <tr><td>{@link Long}</td><td>{@code long}</td></tr>
 *   <tr><td>{@link Double}</td><td>{@code double}</td></tr>
 *   <tr><td>{@link Float}</td><td>{@code float}</td></tr>
 *   <tr><td>{@link Short}</td><td>{@code short}</td></tr>
 *   <tr><td>{@link Boolean}</td><td>{@code bool}</td></tr>
 *   <tr><td>{@link BigDecimal}</td><td>{@code bigdec}</td></tr>
 * </table>
 *
 * @see CursorValueCodec
 * @see PageCursor
 */
public final class CursorCodecs {

    private static final CursorCodecs DEFAULT = buildDefaults();

    private final Map<Class<?>, CursorValueCodec<?>> byType;
    private final Map<String, CursorValueCodec<?>> byPrefix;

    private CursorCodecs(Map<Class<?>, CursorValueCodec<?>> byType, Map<String, CursorValueCodec<?>> byPrefix) {
        this.byType = Map.copyOf(byType);
        this.byPrefix = Map.copyOf(byPrefix);
    }

    /**
     * Returns the default codec set containing all built-in type codecs.
     *
     * @return the shared default {@code CursorCodecs} instance
     */
    public static CursorCodecs defaults() {
        return DEFAULT;
    }

    /**
     * Returns a new {@code CursorCodecs} instance with the given codec added.
     * The original instance is not modified.
     *
     * @param codec the codec to add
     * @return a new instance containing all existing codecs plus the new one
     * @throws NullPointerException     if {@code codec} is null
     * @throws IllegalArgumentException if the codec's prefix or type is already registered
     */
    public CursorCodecs with(CursorValueCodec<?> codec) {
        Objects.requireNonNull(codec, "codec");
        if (byPrefix.containsKey(codec.typePrefix())) {
            throw new IllegalArgumentException("Prefix already registered: " + codec.typePrefix());
        }
        if (byType.containsKey(codec.type())) {
            throw new IllegalArgumentException(
                    "Type already registered: " + codec.type().getName());
        }
        var newByType = new LinkedHashMap<>(byType);
        var newByPrefix = new LinkedHashMap<>(byPrefix);
        newByType.put(codec.type(), codec);
        newByPrefix.put(codec.typePrefix(), codec);
        return new CursorCodecs(newByType, newByPrefix);
    }

    /**
     * Serializes a value to its prefixed string form (e.g., {@code "uuid:550e8400-..."}).
     * Returns {@code "null:"} for null values.
     *
     * @param value the value to serialize (may be null)
     * @return the prefixed serialized form
     * @throws IllegalArgumentException if the value type has no registered codec
     */
    @SuppressWarnings("unchecked")
    public String serialize(Object value) {
        if (value == null) {
            return "null:";
        }
        CursorValueCodec<Object> codec = (CursorValueCodec<Object>) byType.get(value.getClass());
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported keyset value type: "
                    + value.getClass().getName()
                    + ". Register a CursorValueCodec via PageCursor.registerCodec().");
        }
        return codec.typePrefix() + ":" + codec.serialize(value);
    }

    /**
     * Deserializes a prefixed string form back to a Java object.
     *
     * @param encoded the prefixed value (e.g., {@code "uuid:550e8400-..."})
     * @return the deserialized object, or {@code null} for {@code "null:"}
     * @throws IllegalArgumentException if the prefix is not recognized or the format is invalid
     */
    public Object deserialize(String encoded) {
        int colon = encoded.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Invalid keyset value format (missing type prefix): " + encoded);
        }
        String prefix = encoded.substring(0, colon);
        String raw = encoded.substring(colon + 1);
        if ("null".equals(prefix)) {
            return null;
        }
        CursorValueCodec<?> codec = byPrefix.get(prefix);
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported keyset value type prefix: " + prefix
                    + ". Register a CursorValueCodec via PageCursor.registerCodec().");
        }
        return codec.deserialize(raw);
    }

    // --- Builder helpers ---

    private static CursorCodecs buildDefaults() {
        var byType = new LinkedHashMap<Class<?>, CursorValueCodec<?>>();
        var byPrefix = new LinkedHashMap<String, CursorValueCodec<?>>();

        register(byType, byPrefix, CursorValueCodec.of("uuid", UUID.class, UUID::toString, UUID::fromString));
        register(byType, byPrefix, CursorValueCodec.of("instant", Instant.class, Instant::toString, Instant::parse));
        register(
                byType,
                byPrefix,
                CursorValueCodec.of("odt", OffsetDateTime.class, OffsetDateTime::toString, OffsetDateTime::parse));
        register(
                byType,
                byPrefix,
                CursorValueCodec.of("ldt", LocalDateTime.class, LocalDateTime::toString, LocalDateTime::parse));
        register(byType, byPrefix, CursorValueCodec.of("date", LocalDate.class, LocalDate::toString, LocalDate::parse));
        register(byType, byPrefix, CursorValueCodec.of("str", String.class, v -> v, v -> v));
        register(byType, byPrefix, CursorValueCodec.of("int", Integer.class, Object::toString, Integer::parseInt));
        register(byType, byPrefix, CursorValueCodec.of("long", Long.class, Object::toString, Long::parseLong));
        register(byType, byPrefix, CursorValueCodec.of("double", Double.class, Object::toString, Double::parseDouble));
        register(byType, byPrefix, CursorValueCodec.of("bool", Boolean.class, Object::toString, Boolean::parseBoolean));
        register(
                byType,
                byPrefix,
                CursorValueCodec.of("bigdec", BigDecimal.class, BigDecimal::toPlainString, BigDecimal::new));
        register(byType, byPrefix, CursorValueCodec.of("short", Short.class, Object::toString, Short::parseShort));
        register(byType, byPrefix, CursorValueCodec.of("float", Float.class, Object::toString, Float::parseFloat));

        return new CursorCodecs(byType, byPrefix);
    }

    private static void register(
            Map<Class<?>, CursorValueCodec<?>> byType,
            Map<String, CursorValueCodec<?>> byPrefix,
            CursorValueCodec<?> codec) {
        byType.put(codec.type(), codec);
        byPrefix.put(codec.typePrefix(), codec);
    }
}
