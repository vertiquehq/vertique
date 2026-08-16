// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

/**
 * Thrown when {@link AnnotationJsonSchemaGenerator} fails to construct, generate, or canonicalize
 * a JSON Schema document.
 *
 * <p>Every failure this package can raise is normalized to this single bounded type: an
 * unrepresentable {@link java.lang.reflect.Type}, a malformed or conflicting profile schema-type
 * override declaration, a detected structural conflict between a profile fragment and a property's
 * schema metadata, or an unexpected failure from the underlying Victools generator. The original
 * cause, when one exists, is always preserved.
 *
 * <p>The message is bounded to at most 512 UTF-16 code units, including at most 256 code units of
 * resolved type identity, and never includes application values — only the identity of the type,
 * property, or profile involved in the failure.
 *
 * <p>Only this package constructs instances; consumers catch and translate this exception without
 * depending on the underlying Victools exception types.
 */
public final class JsonSchemaGenerationException extends RuntimeException {

    /**
     * Constructs a new exception with the given bounded message and, when available, the
     * underlying cause.
     *
     * @param message the bounded, value-free detail message describing the generation failure
     * @param cause   the underlying exception that triggered this failure, or {@code null} when
     *                none exists
     */
    JsonSchemaGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
