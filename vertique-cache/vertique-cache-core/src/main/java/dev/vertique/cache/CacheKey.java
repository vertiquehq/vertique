// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Ordered logical key components produced by a cache selector function.
 *
 * <p>A {@code CacheKey} carries logical selector values only, and owns their canonical
 * encoding: each component is independently type-framed and framed components are
 * joined with the runtime-owned {@code :} separator, which framed payloads
 * percent-encode and therefore cannot forge. Identity and region material remain
 * runtime-owned; no provider SPI or {@link Cache}/{@link CacheBuilder} method accepts
 * a {@code CacheKey} as a resolved storage key.
 */
public final class CacheKey {

    /**
     * Canonical selector for an explicitly value-independent operation (an empty
     * declared component list). {@code K} is a reserved scalar-family tag, so no framed
     * component or joined tuple can collide with it.
     */
    static final String CONSTANT_SELECTOR = "k2K";

    private final List<Object> components;

    private CacheKey(List<Object> components) {
        this.components = components;
    }

    /** Creates an ordered component tuple; every component must be non-null. */
    public static CacheKey of(Object first, Object... rest) {
        Objects.requireNonNull(first, "cache key component");
        Objects.requireNonNull(rest, "components");
        List<Object> all = new ArrayList<>(1 + rest.length);
        all.add(first);
        for (Object component : rest) {
            all.add(Objects.requireNonNull(component, "cache key component"));
        }
        return new CacheKey(List.copyOf(all));
    }

    List<Object> components() {
        return components;
    }

    /** Canonically encodes one supported scalar as a single-component selector. */
    static String encodeScalar(Object value) {
        if (value == null) throw new IllegalArgumentException("cache selector input must not be null");
        return Scalar.encode(value);
    }

    /**
     * Renders a selector result — one supported scalar or a {@link CacheKey} — into the
     * canonical selector.
     */
    static String render(Object selectorResult) {
        if (selectorResult == null) throw new IllegalArgumentException("cache selector must not be null");
        if (!(selectorResult instanceof CacheKey key)) return Scalar.encode(selectorResult);
        StringBuilder result = new StringBuilder();
        for (Object component : key.components()) {
            if (result.length() > 0) result.append(':');
            result.append(Scalar.encode(component));
        }
        return result.toString();
    }

    private static final class Scalar {
        static String encode(Object value) {
            Objects.requireNonNull(value, "cache selector component");
            String tag;
            String payload;
            if (value instanceof String string) {
                tag = "S";
                payload = text(string);
            } else if (value instanceof Character character) {
                if (Character.isSurrogate(character))
                    throw new IllegalArgumentException("surrogate Character is unsupported");
                tag = "C";
                payload = text(character.toString());
            } else if (value instanceof Boolean) {
                tag = "Z";
                payload = value.toString();
            } else if (value instanceof Byte) {
                tag = "B";
                payload = value.toString();
            } else if (value instanceof Short) {
                tag = "H";
                payload = value.toString();
            } else if (value instanceof Integer) {
                tag = "I";
                payload = value.toString();
            } else if (value instanceof Long) {
                tag = "L";
                payload = value.toString();
            } else if (value instanceof java.math.BigInteger) {
                tag = "N";
                payload = value.toString();
            } else if (value instanceof Float f && Float.isFinite(f)) {
                tag = "F";
                payload = f.toString();
            } else if (value instanceof Double d && Double.isFinite(d)) {
                tag = "D";
                payload = d.toString();
            } else if (value instanceof java.math.BigDecimal decimal) {
                tag = "M";
                payload = decimal.scale() + "~" + decimal.unscaledValue();
            } else if (value instanceof Enum<?> enumeration) {
                tag = "E";
                String type = percent(enumeration.getDeclaringClass().getName());
                payload = type.length() + "~" + type + percent(enumeration.name());
            } else if (value instanceof UUID) {
                tag = "U";
                payload = value.toString();
            } else if (value instanceof java.time.Instant) {
                tag = "T";
                payload = value.toString();
            } else if (value instanceof java.time.LocalDate) {
                tag = "A";
                payload = value.toString();
            } else if (value instanceof java.time.LocalDateTime) {
                tag = "J";
                payload = value.toString();
            } else if (value instanceof java.time.OffsetDateTime) {
                tag = "O";
                payload = value.toString();
            } else if (value instanceof java.time.ZonedDateTime) {
                tag = "W";
                payload = value.toString();
            } else
                throw new IllegalArgumentException("unsupported cache selector component: "
                        + value.getClass().getName());
            return "k2" + tag + percent(payload);
        }

        private static String text(String value) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                        throw new IllegalArgumentException("cache selector contains ill-formed UTF-16");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw new IllegalArgumentException("cache selector contains ill-formed UTF-16");
                }
            }
            return Normalizer.normalize(value, Normalizer.Form.NFC);
        }

        private static String percent(String value) {
            StringBuilder result = new StringBuilder();
            for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                char ch = (char) (b & 0xff);
                if (ch >= 'A' && ch <= 'Z'
                        || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9'
                        || "._~%-".indexOf(ch) >= 0) result.append(ch);
                else result.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
            return result.toString();
        }
    }
}
