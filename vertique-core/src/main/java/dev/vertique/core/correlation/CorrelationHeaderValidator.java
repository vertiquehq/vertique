// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Utility class for validating and sanitizing HTTP header names and values used in correlation
 * context propagation.
 *
 * <p>Header name validation enforces the RFC 7230 token grammar. Header value validation enforces
 * a conservative allow-list pattern ({@code [A-Za-z0-9._~:/+=\-]+}) with explicit rejection of
 * control characters, CR, LF, NUL, and non-ASCII characters.
 *
 * <p>In V1, {@link #sanitizeForResponse(String)} is validate-or-drop: it passes valid values
 * through and returns {@link Optional#empty()} for anything that does not conform. No
 * transformation or encoding is applied.
 *
 * <p>This class is not instantiable.
 */
public final class CorrelationHeaderValidator {

    /** Maximum allowed length for a correlation header name. */
    public static final int MAX_HEADER_NAME_LENGTH = 64;

    /** Maximum allowed length for a correlation header value. */
    public static final int MAX_HEADER_VALUE_LENGTH = 128;

    // --- compiled patterns ---

    /**
     * RFC 7230 token grammar: {@code token = 1*tchar} where
     * {@code tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
     * "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA}.
     */
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

    /**
     * Conservative allow-list for correlation header values.
     * Permits: {@code A-Za-z0-9 . _ ~ : / + = -}.
     */
    private static final Pattern HEADER_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9._~:/+=\\-]+$");

    private CorrelationHeaderValidator() {}

    // --- public API ---

    /**
     * Returns {@code true} if {@code name} is a valid HTTP header name according to the
     * RFC 7230 token grammar and within the maximum length.
     *
     * @param name the header name to check; {@code null} returns {@code false}
     * @return {@code true} when the name is a valid non-blank RFC 7230 token of at most
     *         {@value #MAX_HEADER_NAME_LENGTH} characters
     */
    public static boolean isValidHeaderName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.length() > MAX_HEADER_NAME_LENGTH) {
            return false;
        }
        return HEADER_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Returns {@code true} if {@code value} is a valid correlation header value.
     *
     * <p>A valid value is non-null, non-blank, at most {@value #MAX_HEADER_VALUE_LENGTH} characters
     * long, matches the allow-list pattern {@code [A-Za-z0-9._~:/+=\-]+}, and contains no control
     * characters, CR, LF, NUL, or non-ASCII characters.
     *
     * @param value the header value to check; {@code null} returns {@code false}
     * @return {@code true} when the value conforms to all constraints
     */
    public static boolean isValidHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > MAX_HEADER_VALUE_LENGTH) {
            return false;
        }
        return HEADER_VALUE_PATTERN.matcher(value).matches();
    }

    /**
     * Asserts that {@code name} is a valid header name according to
     * {@link #isValidHeaderName(String)}.
     *
     * @param name the header name to validate
     * @throws IllegalArgumentException if {@code name} is invalid
     */
    public static void requireValidHeaderName(String name) {
        if (!isValidHeaderName(name)) {
            throw new IllegalArgumentException("Invalid correlation header name: '" + name
                    + "'. Must be a non-blank RFC 7230 token of at most " + MAX_HEADER_NAME_LENGTH + " characters.");
        }
    }

    /**
     * Asserts that {@code value} is a valid header value according to
     * {@link #isValidHeaderValue(String)}.
     *
     * @param value the header value to validate
     * @throws IllegalArgumentException if {@code value} is invalid
     */
    public static void requireValidHeaderValue(String value) {
        if (!isValidHeaderValue(value)) {
            throw new IllegalArgumentException(
                    "Invalid correlation header value: value must be non-blank, at most " + MAX_HEADER_VALUE_LENGTH
                            + " characters, and match the allowed character set [A-Za-z0-9._~:/+=-].");
        }
    }

    /**
     * Returns the value unchanged if it passes {@link #isValidHeaderValue(String)} validation,
     * or {@link Optional#empty()} otherwise.
     *
     * <p>This is a validate-or-drop strategy: no transformation or encoding is performed.
     *
     * @param value the header value candidate; may be {@code null}
     * @return the value wrapped in {@link Optional} if valid, otherwise {@link Optional#empty()}
     */
    public static Optional<String> sanitizeForResponse(String value) {
        return isValidHeaderValue(value) ? Optional.of(value) : Optional.empty();
    }
}
