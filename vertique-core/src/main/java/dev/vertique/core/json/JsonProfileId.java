// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

/**
 * Immutable, case-sensitive identifier for a named JSON mapper profile.
 *
 * <p>A profile id selects a code-owned {@code ObjectMapper} configuration at a framework JSON
 * boundary. The reserved {@link #VERTX} id designates the zero-config default profile backed by
 * Vert.x's {@code DatabindCodec.mapper()}.
 *
 * <p>The value is normalized by trimming surrounding whitespace; it must be non-null and
 * non-blank. Equality and hash code are derived from the trimmed value and are case-sensitive
 * (the record's default componentwise semantics).
 *
 * @param value the normalized, non-blank profile id (whitespace-trimmed)
 */
public record JsonProfileId(String value) {

    /** The reserved id for the zero-config default profile backed by Vert.x's databind mapper. */
    public static final JsonProfileId VERTX = new JsonProfileId("vertx");

    /**
     * Validates and normalizes the profile id.
     *
     * @throws IllegalArgumentException if {@code value} is {@code null} or, after trimming, blank
     */
    public JsonProfileId {
        if (value == null) {
            throw new IllegalArgumentException("JsonProfileId value must not be null");
        }
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("JsonProfileId value must not be blank");
        }
    }

    /**
     * Creates a profile id from the given value, applying the same validation and normalization as
     * the canonical constructor.
     *
     * @param value the raw profile id; trimmed and validated as non-blank
     * @return a normalized {@link JsonProfileId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or, after trimming, blank
     */
    public static JsonProfileId of(String value) {
        return new JsonProfileId(value);
    }
}
