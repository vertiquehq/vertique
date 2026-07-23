// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import java.util.Objects;
import java.util.Set;

/**
 * Configuration record for the trusted-proxy CIDR policy used by {@link RequestOriginCapturer}.
 *
 * <p>Controls which inbound proxy addresses are trusted for forwarded-header resolution. An empty
 * {@code trustedProxyCidrs} set (the default) means no proxy is trusted: {@code clientIp} will
 * always equal {@code remoteIp}, and forwarded scheme/host headers are never used (regardless of
 * the {@code trustForwarded*} flags).
 *
 * <p>Production deployments running behind a load balancer or reverse proxy should configure the
 * proxy's outbound CIDR range. For example:
 * <pre>{@code
 * new RequestOriginConfig(Set.of("10.0.0.0/8", "172.16.0.0/12"), 16, true, true)
 * }</pre>
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code trustedProxyCidrs} must not be {@code null}; the set is defensively copied.</li>
 *   <li>{@code forwardedForCap} must be {@code >= 0}; {@code 0} disables the chain entirely.</li>
 * </ul>
 *
 * @param trustedProxyCidrs   CIDR notation strings ({@code "10.0.0.0/8"}, {@code "127.0.0.1/32"},
 *                            etc.) of addresses that are trusted to set forwarded headers; an empty
 *                            set means trust nothing
 * @param forwardedForCap     maximum number of {@code X-Forwarded-For} chain entries to accept;
 *                            chains longer than this are dropped entirely with
 *                            {@code forwardedForChainRejected=true}; must be {@code >= 0}
 * @param trustForwardedScheme when {@code true} and the direct peer is trusted, the
 *                             {@code X-Forwarded-Proto} header overrides the connection scheme
 * @param trustForwardedHost   when {@code true} and the direct peer is trusted, the
 *                             {@code X-Forwarded-Host} header overrides the {@code Host} header
 */
public record RequestOriginConfig(
        Set<String> trustedProxyCidrs, int forwardedForCap, boolean trustForwardedScheme, boolean trustForwardedHost) {

    /** Default maximum number of X-Forwarded-For chain entries. */
    public static final int DEFAULT_FORWARDED_FOR_CAP = 16;

    /**
     * Compact constructor — validates fields and defensively copies the CIDR set.
     */
    public RequestOriginConfig {
        Objects.requireNonNull(trustedProxyCidrs, "trustedProxyCidrs");
        trustedProxyCidrs = Set.copyOf(trustedProxyCidrs);
        if (forwardedForCap < 0) {
            throw new IllegalArgumentException("forwardedForCap must be >= 0");
        }
    }

    /**
     * Returns the default configuration: trust no proxy, cap chain at
     * {@value #DEFAULT_FORWARDED_FOR_CAP} entries, do not trust forwarded scheme or host.
     *
     * @return a default {@link RequestOriginConfig} instance
     */
    public static RequestOriginConfig defaults() {
        return new RequestOriginConfig(Set.of(), DEFAULT_FORWARDED_FOR_CAP, false, false);
    }
}
