// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.lang.reflect.Type;

/**
 * Builds the bounded, value-free diagnostics every {@link JsonSchemaGenerationException} carries.
 *
 * <p>Two bounds are enforced: a whole message is at most {@value #MAX_MESSAGE_LENGTH} UTF-16 code
 * units, and any single resolved-type identity inside it is at most {@value #MAX_TYPE_IDENTITY_LENGTH}.
 * A deeply nested generic type can otherwise produce an unbounded type name, and a failure message
 * is frequently logged verbatim.
 *
 * <p>Only <em>identity</em> ever reaches a message — a type name, a property name, a profile id.
 * Application values never do.
 */
final class Diagnostics {

    /** Maximum length, in UTF-16 code units, of a complete failure message. */
    static final int MAX_MESSAGE_LENGTH = 512;

    /** Maximum length, in UTF-16 code units, of one resolved-type identity inside a message. */
    static final int MAX_TYPE_IDENTITY_LENGTH = 256;

    /** Marker appended in place of the elided tail of a truncated fragment. */
    private static final String ELLIPSIS = "...";

    private Diagnostics() {}

    /**
     * Renders a bounded identity for a resolved {@link Type}.
     *
     * <p>A known reflection form is rendered through {@link Type#getTypeName()}. An unknown custom
     * {@code Type} implementation is rendered by its <em>class</em> name instead, because its
     * {@code getTypeName()} is caller-supplied and could carry arbitrary text.
     *
     * @param type the type to identify, possibly {@code null}
     * @return the bounded identity
     */
    static String typeIdentity(Type type) {
        if (type == null) {
            return "null";
        }
        String name;
        if (TypeGrammar.isKnownForm(type)) {
            try {
                name = type.getTypeName();
            } catch (RuntimeException unavailable) {
                name = type.getClass().getName();
            }
        } else {
            name = type.getClass().getName();
        }
        return truncate(name, MAX_TYPE_IDENTITY_LENGTH);
    }

    /**
     * Truncates a message fragment to a maximum length, marking the elision.
     *
     * @param value the fragment, possibly {@code null}
     * @param max   the maximum retained length in UTF-16 code units
     * @return the bounded fragment
     */
    static String truncate(String value, int max) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - ELLIPSIS.length())) + ELLIPSIS;
    }

    /**
     * Builds a bounded generation failure.
     *
     * @param message the value-free message, bounded to {@value #MAX_MESSAGE_LENGTH} code units
     * @param cause   the underlying cause, or {@code null} when none exists
     * @return the exception to throw
     */
    static JsonSchemaGenerationException failure(String message, Throwable cause) {
        return new JsonSchemaGenerationException(truncate(message, MAX_MESSAGE_LENGTH), cause);
    }
}
