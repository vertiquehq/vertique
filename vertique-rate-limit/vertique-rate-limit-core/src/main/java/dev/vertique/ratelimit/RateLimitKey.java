// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, canonical key construction from scalar components.
 *
 * <p>Components use the same type-framed scalar encoding discipline as {@code
 * dev.vertique.cache.CacheKey} (read-only precedent; not a shared dependency —
 * {@code spec.md} §14 defers a shared extraction): each component is independently framed as a
 * single type tag plus a percent-encoded payload, and framed components are joined with an
 * unforgeable {@code :} separator — framed payloads can only contain the tag alphabet, so no raw
 * component value can forge the join. Distinct component tuples therefore always produce
 * distinct canonical encodings ({@code contracts/rate-limit-runtime.md}, "Key model").
 */
public final class RateLimitKey {

    /**
     * Canonical encoding for the explicit zero-component key. {@code G} is a reserved tag: no
     * allowlisted scalar type frames under it, so no non-empty component tuple's encoding can
     * ever equal this single-character string, even a single component with an empty payload.
     */
    private static final String GLOBAL_ENCODING = "G";

    private final String canonicalEncoding;

    private RateLimitKey(String canonicalEncoding) {
        this.canonicalEncoding = canonicalEncoding;
    }

    /**
     * Builds a key from one or more scalar components. Component order is significant. Each
     * component must be one of the allowlisted scalar types; {@code NaN}/infinite {@code
     * Float}/{@code Double} values and any other type are rejected.
     *
     * @param first first, required component
     * @param rest additional components, if any
     * @return a key whose canonical encoding is distinct for distinct component tuples
     * @throws NullPointerException if {@code first} or any component in {@code rest} is {@code
     *     null}
     * @throws IllegalArgumentException if a component is not an allowlisted scalar type, or is a
     *     non-finite {@code Float}/{@code Double}
     */
    public static RateLimitKey of(Object first, Object... rest) {
        Objects.requireNonNull(first, "first");
        StringBuilder encoding = new StringBuilder();
        encoding.append(Scalar.encode(first));
        if (rest != null) {
            for (Object component : rest) {
                encoding.append(':').append(Scalar.encode(component));
            }
        }
        return new RateLimitKey(encoding.toString());
    }

    /**
     * The explicit zero-component key — distinct from the canonical encoding of any non-empty
     * component tuple.
     *
     * @return the shared global key
     */
    public static RateLimitKey global() {
        return new RateLimitKey(GLOBAL_ENCODING);
    }

    /**
     * @return the canonical encoding; engine-private, consumed by the storage-identity encoder
     *     and never exposed as public API
     */
    String canonicalEncoding() {
        return canonicalEncoding;
    }

    /**
     * Two keys are equal exactly when their canonical encodings are equal — i.e. exactly when
     * they were built from the same component tuple (or are both {@link #global()}). Public so
     * adapter seams outside this package (e.g. {@code dev.vertique.ratelimit.spi.RateLimitAdapterSupport})
     * can prove distinctness/stability of a framed key without access to the engine-private
     * encoding itself.
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof RateLimitKey other && canonicalEncoding.equals(other.canonicalEncoding);
    }

    @Override
    public int hashCode() {
        return canonicalEncoding.hashCode();
    }

    /**
     * Per-component type-tag encoding: one letter tag plus a percent-encoded payload, bounded to
     * 256 bytes UTF-8 post-encoding ({@code contracts/rate-limit-runtime.md},
     * "Key-component byte bound"). A component that exceeds the bound is deterministically
     * replaced by {@code h:} followed by the lowercase-hex {@code SHA-256} digest of its
     * pre-encoding payload — fixed-width and distinctness-preserving, never a rejection or a
     * silent truncation. {@code h:} can never collide with a real tag: every allowlisted tag is
     * an uppercase letter, while the substitution marker starts with lowercase {@code h}.
     */
    private static final class Scalar {

        private static final int MAX_COMPONENT_BYTES = 256;

        private Scalar() {}

        static String encode(Object value) {
            Objects.requireNonNull(value, "component");
            String tag;
            String payload;
            if (value instanceof String string) {
                tag = "S";
                payload = text(string);
            } else if (value instanceof Character character) {
                if (Character.isSurrogate(character)) {
                    throw new IllegalArgumentException("surrogate Character is unsupported");
                }
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
            } else if (value instanceof BigInteger) {
                tag = "N";
                payload = value.toString();
            } else if (value instanceof Float f) {
                if (!Float.isFinite(f)) {
                    throw new IllegalArgumentException("non-finite Float is unsupported");
                }
                tag = "F";
                payload = f.toString();
            } else if (value instanceof Double d) {
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("non-finite Double is unsupported");
                }
                tag = "D";
                payload = d.toString();
            } else if (value instanceof BigDecimal decimal) {
                tag = "M";
                payload = decimal.scale() + "~" + decimal.unscaledValue();
            } else if (value instanceof Enum<?> enumeration) {
                tag = "E";
                String type = percent(enumeration.getDeclaringClass().getName());
                payload = type.length() + "~" + type + percent(enumeration.name());
            } else if (value instanceof UUID) {
                tag = "U";
                payload = value.toString();
            } else if (value instanceof Instant) {
                tag = "T";
                payload = value.toString();
            } else if (value instanceof LocalDate) {
                tag = "A";
                payload = value.toString();
            } else if (value instanceof LocalDateTime) {
                tag = "J";
                payload = value.toString();
            } else if (value instanceof OffsetDateTime) {
                tag = "O";
                payload = value.toString();
            } else if (value instanceof ZonedDateTime) {
                tag = "W";
                payload = value.toString();
            } else {
                throw new IllegalArgumentException("unsupported rate-limit key component: "
                        + value.getClass().getName());
            }
            String framed = tag + percent(payload);
            if (utf8ByteLength(framed) <= MAX_COMPONENT_BYTES) {
                return framed;
            }
            return "h:" + sha256Hex(payload);
        }

        private static String text(String value) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                        throw new IllegalArgumentException("rate-limit key component contains ill-formed UTF-16");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw new IllegalArgumentException("rate-limit key component contains ill-formed UTF-16");
                }
            }
            return Normalizer.normalize(value, Normalizer.Form.NFC);
        }

        private static String percent(String value) {
            StringBuilder result = new StringBuilder();
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                char ch = (char) (b & 0xff);
                if (ch >= 'A' && ch <= 'Z'
                        || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9'
                        || "._~-".indexOf(ch) >= 0) result.append(ch);
                else result.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
            return result.toString();
        }

        private static int utf8ByteLength(String value) {
            return value.getBytes(StandardCharsets.UTF_8).length;
        }

        private static String sha256Hex(String preEncodingValue) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(preEncodingValue.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
