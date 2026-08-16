// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Identifies where an input value originates, across transports — HTTP request parts and
 * message/protocol payloads.
 *
 * <p>Used by {@link InputValueContext} to give canonicalizers and sanitizers information about
 * the source of a value so they can apply location-appropriate normalization rules.
 */
public enum InputLocation {

    /** Value extracted from a URI path segment (e.g., {@code /users/{id}}). */
    PATH,

    /** Value extracted from a URI query parameter (e.g., {@code ?name=foo}). */
    QUERY,

    /** Value extracted from an HTTP request header. */
    HEADER,

    /** Value extracted from an HTTP cookie. */
    COOKIE,

    /** Value extracted from an HTML form field ({@code application/x-www-form-urlencoded} or multipart). */
    FORM,

    /** Value extracted from the request body (e.g., a JSON field). */
    BODY,

    /** Value extracted and aggregated via a JAX-RS {@code @BeanParam} container. */
    BEAN_PARAM,

    /** Value extracted from a message or protocol payload. */
    PAYLOAD
}
