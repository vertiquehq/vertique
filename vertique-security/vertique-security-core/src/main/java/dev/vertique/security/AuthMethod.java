// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;

/**
 * Authentication method used to establish a {@link SecurityContext}.
 *
 * <p>Implementations describe how the caller was authenticated (JWT, API key, mTLS, etc.).
 * Each auth method has a stable string {@link #id()}, a normalized {@link AuthMethodKind},
 * and optional provider-specific {@link #attributes()}.
 *
 * <p>Standard implementations are available via {@link DefaultAuthMethod} factory methods.
 *
 * @see DefaultAuthMethod
 * @see AuthMethodKind
 */
public interface AuthMethod {

    /**
     * Stable identifier for this authentication method (e.g. "jwt", "api_key", "basic", "mtls").
     *
     * @return the method identifier, never null
     */
    String id();

    /**
     * Normalized kind for cross-module switching.
     *
     * @return the normalized kind, never null
     */
    AuthMethodKind normalizedKind();

    /**
     * Provider-specific metadata associated with this authentication method.
     *
     * @return an unmodifiable map of attributes, never null
     */
    Map<String, Object> attributes();
}
