// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.origin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable network-envelope snapshot captured before authentication runs.
 *
 * <p>Records the direct peer address, the parsed {@code X-Forwarded-For} chain with rejection
 * accounting, the derived {@code clientIp} according to the trusted-proxy policy, the effective
 * request scheme and host, and optional TLS handshake facts.
 *
 * <p>The trusted-proxy policy that drives {@code clientIp} derivation lives in the REST layer
 * ({@code OriginCaptureMiddleware}). This record is transport-neutral and carries no policy logic
 * of its own.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code remoteIp}, {@code clientIp}, {@code scheme}, and {@code host} are required
 *       (non-null, non-blank).</li>
 *   <li>{@code remotePort} must be in the range {@code [0, 65535]}.</li>
 *   <li>A {@code null} {@code forwardedFor} list is treated as {@link List#of()} (empty); the
 *       list is defensively copied and the returned accessor is unmodifiable.</li>
 *   <li>{@code forwardedForRejectedCount} must be {@code >= 0}.</li>
 *   <li>{@code tls} must be a non-null {@link Optional} (use {@link Optional#empty()} for
 *       plain HTTP).</li>
 * </ul>
 *
 * @param remoteIp                    IP address of the direct peer (TCP remote address); non-null,
 *                                    non-blank
 * @param remotePort                  TCP port of the direct peer; {@code [0, 65535]}
 * @param forwardedFor                parsed and sanitized {@code X-Forwarded-For} entries;
 *                                    never {@code null} after construction
 * @param forwardedForRejectedCount   number of {@code X-Forwarded-For} entries that were dropped
 *                                    due to invalid format or cap overflow; {@code >= 0}
 * @param forwardedForChainRejected   {@code true} when the entire forwarded chain was discarded
 *                                    (e.g. cap exceeded); individual entries may still be present
 *                                    for forensic observability
 * @param clientIp                    derived effective client IP after applying the trusted-proxy
 *                                    policy; non-null, non-blank
 * @param scheme                      effective request scheme ({@code "http"} or {@code "https"});
 *                                    non-null, non-blank
 * @param host                        effective request host (validated {@code Host} header or
 *                                    forwarded host from a trusted proxy); non-null, non-blank
 * @param tls                         TLS handshake facts for the direct connection; non-null
 *                                    {@link Optional} — use {@link Optional#empty()} when the
 *                                    connection is plain HTTP
 */
public record RequestOrigin(
        String remoteIp,
        int remotePort,
        List<String> forwardedFor,
        int forwardedForRejectedCount,
        boolean forwardedForChainRejected,
        String clientIp,
        String scheme,
        String host,
        Optional<TlsFacts> tls) {

    /**
     * Compact constructor — validates all required fields, enforces port range, and defensively
     * copies the {@code forwardedFor} list.
     */
    public RequestOrigin {
        Objects.requireNonNull(remoteIp, "remoteIp");
        if (remoteIp.isBlank()) {
            throw new IllegalArgumentException("remoteIp must not be blank");
        }
        if (remotePort < 0 || remotePort > 65535) {
            throw new IllegalArgumentException("remotePort out of range: " + remotePort);
        }
        forwardedFor = List.copyOf(forwardedFor == null ? List.of() : forwardedFor);
        if (forwardedForRejectedCount < 0) {
            throw new IllegalArgumentException("forwardedForRejectedCount must be >= 0");
        }
        Objects.requireNonNull(clientIp, "clientIp");
        if (clientIp.isBlank()) {
            throw new IllegalArgumentException("clientIp must not be blank");
        }
        Objects.requireNonNull(scheme, "scheme");
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        Objects.requireNonNull(tls, "tls");
    }
}
