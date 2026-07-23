// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

/**
 * Describes the expected shape of a token introspection response for an
 * {@link IntrospectionVerificationSource}.
 */
public enum IntrospectionResponseShape {

    /** Standard RFC 7662 JSON response body: {@code {"active": true, ...}}. */
    RFC7662_JSON,

    /** Introspection response is a compact signed JWT (JWS). */
    SIGNED_JWT,

    /** Server may return either RFC 7662 JSON or a signed JWT; clients should accept both. */
    BOTH
}
