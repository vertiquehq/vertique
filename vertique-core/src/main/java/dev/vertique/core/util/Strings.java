// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared string-manipulation utilities used across multiple framework modules.
 *
 * <p>These are small, pure functions with no external dependencies. They live in
 * {@code vertique-core} so every consuming module (rest-jaxrs, kafka-core, etc.) can import a
 * single definition instead of maintaining private copies.
 *
 * <p>This class is a final utility class and cannot be instantiated.
 */
public final class Strings {

    /** The error type that stands in for one that is not a simple token. */
    public static final String INVALID_ERROR_TYPE = "InvalidErrorType";

    private static final Pattern ERROR_TYPE_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.\\-]{0,127}");

    private Strings() {}

    /**
     * Returns the first candidate that is non-{@code null} and non-blank, or {@code null} when all
     * candidates are {@code null} or blank.
     *
     * <p>Candidates are tested left-to-right; the first one that passes
     * {@code value != null && !value.isBlank()} is returned. This makes the varargs form a natural
     * n-ary extension of the pairwise {@code firstNonBlank(primary, fallback)} pattern:
     *
     * <pre>{@code
     * // before — nested pairs:
     * String a = firstNonBlank(firstNonBlank(method, producerConfig), annotation);
     *
     * // after — flat varargs:
     * String a = Strings.firstNonBlank(method, producerConfig, annotation);
     * }</pre>
     *
     * @param candidates zero or more candidate strings, tested in order; may individually be
     *     {@code null} or blank
     * @return the first non-{@code null}, non-blank candidate, or {@code null} if none is found
     */
    @Nullable
    public static String firstNonBlank(@Nullable String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the error type when it is a simple token, and {@value #INVALID_ERROR_TYPE} otherwise, so
     * that a value which arrives from outside cannot reach a log line, a header or an audit field.
     *
     * <p>A simple token is a letter, {@code _} or {@code $} followed by up to 127 letters, digits and
     * {@code _ $ . -}. Text with a space or a control character, an empty text and a longer text are not.
     *
     * @param errorType the error type to check
     * @return {@code errorType} itself when it is a simple token, otherwise {@value #INVALID_ERROR_TYPE}
     * @throws NullPointerException if {@code errorType} is {@code null}
     */
    public static String errorTypeToken(String errorType) {
        Objects.requireNonNull(errorType, "errorType");
        return ERROR_TYPE_TOKEN.matcher(errorType).matches() ? errorType : INVALID_ERROR_TYPE;
    }
}
