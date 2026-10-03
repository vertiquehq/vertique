// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security.scheme;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * An API key security scheme description (OpenAPI {@code type: apiKey}), carried in a header,
 * query parameter, or cookie. Immutable; built through {@link #header(String)},
 * {@link #query(String)}, or {@link #cookie(String)}.
 *
 * <p>A header or cookie name must be an RFC 9110 token: one or more characters, each an ASCII
 * letter, a digit, or one of {@code !#$%&'*+-.^_`|~}. A header scheme's name can reach response
 * headers such as {@code Vary}, so any other character, including whitespace, a control character,
 * or a non-ASCII letter, is rejected when the description is built. A query parameter name reaches
 * no header and need only be non-blank.
 */
public final class ApiKey implements SecuritySchemeDescription {

    private final String name;
    private final Location in;

    @Nullable
    private final String description;

    private ApiKey(String name, Location in, @Nullable String description) {
        this.name = name;
        this.in = in;
        this.description = description;
    }

    /**
     * Describes an API key carried in a request header.
     *
     * @param name the header name; an RFC 9110 token
     * @return a new {@link ApiKey} description
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is not an RFC 9110 token
     */
    public static ApiKey header(String name) {
        return new ApiKey(requireToken(name), Location.HEADER, null);
    }

    /**
     * Describes an API key carried in a query parameter.
     *
     * @param name the query parameter name
     * @return a new {@link ApiKey} description
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static ApiKey query(String name) {
        return new ApiKey(requireNonBlank(name, "name"), Location.QUERY, null);
    }

    /**
     * Describes an API key carried in a cookie.
     *
     * @param name the cookie name; an RFC 9110 token
     * @return a new {@link ApiKey} description
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is not an RFC 9110 token
     */
    public static ApiKey cookie(String name) {
        return new ApiKey(requireToken(name), Location.COOKIE, null);
    }

    /**
     * Returns a copy of this description with the given human-readable description.
     *
     * @param description the description text
     * @return a new {@link ApiKey} instance; this instance is unchanged
     * @throws NullPointerException     if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public ApiKey withDescription(String description) {
        return new ApiKey(name, in, requireNonBlank(description, "description"));
    }

    /**
     * The header, query parameter, or cookie name carrying the key.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Where the key is carried.
     *
     * @return the location
     */
    public Location in() {
        return in;
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    // --- Object contract ---

    /**
     * Two {@code ApiKey} descriptions are equal when their name, location, and description are
     * equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects describe the same scheme
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ApiKey other)) {
            return false;
        }
        return name.equals(other.name) && in == other.in && Objects.equals(description, other.description);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, in, description);
    }

    /**
     * Returns a string of this description's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "ApiKey[name=" + name + ", in=" + in + ", description=" + description + "]";
    }

    // --- Validation ---

    private static String requireNonBlank(String value, String argument) {
        Objects.requireNonNull(value, argument + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(argument + " must not be blank");
        }
        return value;
    }

    /**
     * Checks that {@code name} is an RFC 9110 token in one pass over its characters. The rejection
     * message names the offending position, never the value, so a control character in the input
     * never reaches a log line.
     */
    private static String requireToken(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must be an RFC 9110 token, but is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isTokenChar(name.charAt(i))) {
                throw new IllegalArgumentException(
                        "name must be an RFC 9110 token, but has a non-token character at index " + i);
            }
        }
        return name;
    }

    /** Whether {@code c} is an RFC 9110 {@code tchar}: an ASCII letter, a digit, or listed punctuation. */
    private static boolean isTokenChar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return switch (c) {
            case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
            default -> false;
        };
    }

    /** Where an {@link ApiKey} is carried on the request. */
    public enum Location {
        /** A query parameter. */
        QUERY,
        /** A request header. */
        HEADER,
        /** A cookie. */
        COOKIE
    }
}
