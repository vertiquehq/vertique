// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Normalized taxonomy of authentication method kinds.
 *
 * <p>Provides a stable set of values that cross-module code (authorization handlers,
 * JAX-RS security context bridge, audit identity) can switch on, independent of
 * provider-specific {@link AuthMethod#id()} values.
 *
 * @see AuthMethod#normalizedKind()
 */
public enum AuthMethodKind {

    /** No authentication performed. */
    NONE,

    /** JSON Web Token (Bearer token). */
    JWT,

    /** API key authentication. */
    API_KEY,

    /** HTTP Basic authentication. */
    BASIC,

    /** Mutual TLS (client certificate) authentication. */
    MTLS,

    /** HMAC (Hash-based Message Authentication Code) authentication. */
    HMAC,

    /** Custom authentication method not covered by standard kinds. */
    CUSTOM,

    /** Authentication method could not be determined. */
    UNKNOWN
}
