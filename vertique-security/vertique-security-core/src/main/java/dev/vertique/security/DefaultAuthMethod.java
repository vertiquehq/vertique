// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;

/**
 * Immutable standard implementation of {@link AuthMethod}.
 *
 * <p>Provides factory methods for common authentication methods:
 * <ul>
 *   <li>{@link #none()} — no authentication</li>
 *   <li>{@link #jwt()} — JSON Web Token</li>
 *   <li>{@link #apiKey()} — API key</li>
 *   <li>{@link #basic()} — HTTP Basic</li>
 *   <li>{@link #mtls()} — mutual TLS</li>
 *   <li>{@link #hmac()} — HMAC authentication</li>
 *   <li>{@link #unknown()} — unresolvable method</li>
 *   <li>{@link #custom(String)} — custom method with specified id</li>
 * </ul>
 *
 * @param id             stable identifier for this method
 * @param normalizedKind normalized kind for cross-module switching
 * @param attributes     provider-specific metadata (defensively copied)
 */
public record DefaultAuthMethod(String id, AuthMethodKind normalizedKind, Map<String, Object> attributes)
        implements AuthMethod {

    /**
     * Compact constructor — defensive copy for immutability.
     */
    public DefaultAuthMethod {
        attributes = Map.copyOf(attributes);
    }

    /**
     * Returns an {@link AuthMethod} representing no authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#NONE}
     */
    public static AuthMethod none() {
        return new DefaultAuthMethod("none", AuthMethodKind.NONE, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing JSON Web Token (Bearer token) authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#JWT}
     */
    public static AuthMethod jwt() {
        return new DefaultAuthMethod("jwt", AuthMethodKind.JWT, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing API key authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#API_KEY}
     */
    public static AuthMethod apiKey() {
        return new DefaultAuthMethod("api_key", AuthMethodKind.API_KEY, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing HTTP Basic authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#BASIC}
     */
    public static AuthMethod basic() {
        return new DefaultAuthMethod("basic", AuthMethodKind.BASIC, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing mutual TLS (client certificate) authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#MTLS}
     */
    public static AuthMethod mtls() {
        return new DefaultAuthMethod("mtls", AuthMethodKind.MTLS, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing HMAC (Hash-based Message Authentication Code)
     * authentication.
     *
     * @return an auth method with kind {@link AuthMethodKind#HMAC}
     */
    public static AuthMethod hmac() {
        return new DefaultAuthMethod("hmac", AuthMethodKind.HMAC, Map.of());
    }

    /**
     * Returns an {@link AuthMethod} representing an unresolvable authentication method.
     *
     * @return an auth method with kind {@link AuthMethodKind#UNKNOWN}
     */
    public static AuthMethod unknown() {
        return new DefaultAuthMethod("unknown", AuthMethodKind.UNKNOWN, Map.of());
    }

    /**
     * Returns a custom {@link AuthMethod} with the specified identifier.
     *
     * @param id the custom method identifier
     * @return a custom auth method with kind {@link AuthMethodKind#CUSTOM}
     */
    public static AuthMethod custom(String id) {
        return new DefaultAuthMethod(id, AuthMethodKind.CUSTOM, Map.of());
    }

    /**
     * Returns a custom {@link AuthMethod} with the specified identifier and attributes.
     *
     * @param id    the custom method identifier
     * @param attrs provider-specific attributes
     * @return a custom auth method with kind {@link AuthMethodKind#CUSTOM} and the given attributes
     */
    public static AuthMethod custom(String id, Map<String, Object> attrs) {
        return new DefaultAuthMethod(id, AuthMethodKind.CUSTOM, attrs);
    }
}
