// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.origin;

import java.util.Objects;

/**
 * Immutable snapshot of TLS handshake facts for the direct connection to the server.
 *
 * <p>Populated from the Vert.x {@code SSLSession} when the connection uses TLS. The values reflect
 * the <em>direct</em> TLS handshake between the client and this server (or the last hop, when a
 * trusted proxy terminates TLS). They are not forwarded claims and are therefore not subject to
 * the trusted-proxy policy.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code protocol} and {@code cipherSuite} must not be {@code null}.</li>
 *   <li>Empty-string values are accepted for both fields — the TLS handshake session info may
 *       not yet be fully populated at capture time (e.g. mid-handshake tracing).</li>
 * </ul>
 *
 * @param protocol          negotiated TLS protocol version (e.g. {@code "TLSv1.3"}); non-null,
 *                          may be empty
 * @param cipherSuite       negotiated cipher suite (e.g. {@code "TLS_AES_256_GCM_SHA384"});
 *                          non-null, may be empty
 * @param peerCertPresented {@code true} if the peer (client) presented a certificate during the
 *                          TLS handshake (i.e. mTLS was attempted)
 */
public record TlsFacts(String protocol, String cipherSuite, boolean peerCertPresented) {

    /**
     * Compact constructor — validates that required fields are non-null.
     *
     * <p>Empty-string {@code protocol} and {@code cipherSuite} are allowed so that callers can
     * construct a {@code TlsFacts} instance when the SSL session info is not yet fully available.
     */
    public TlsFacts {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(cipherSuite, "cipherSuite");
    }
}
