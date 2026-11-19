// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * The settings of one built client that the governance interceptors and the delegate read.
 *
 * @param basePath the path prefix of relative requests, empty when the base URL has none
 * @param hasBaseUrl whether the client has a base URL
 * @param defaultHeaders headers added to every request that does not set them
 * @param readTimeoutMs the default attempt timeout in milliseconds, 0 for none
 * @param maxRequestBytes the most request body bytes sent, 0 for no bound
 * @param maxResponseBytes the most response body bytes read, 0 for no bound
 * @param streamIdleTimeoutMs the longest time a stream may go without a received body byte in milliseconds, 0 for
 *     none
 * @param maxEventBytes the most raw bytes of one server-sent event, above 0
 * @param egress the policy every request target is checked against
 * @param credentials the source of per-exchange credential headers, or null for none
 * @param allowCredentialsOverPlainHttp whether credentials may travel over plain HTTP to a non-loopback host
 * @param credentialOrigins the extra origins ({@code scheme://host:port}, normalized) credentials may go to
 */
record ClientSettings(
        String basePath,
        boolean hasBaseUrl,
        Map<String, String> defaultHeaders,
        long readTimeoutMs,
        long maxRequestBytes,
        long maxResponseBytes,
        long streamIdleTimeoutMs,
        int maxEventBytes,
        EgressPolicy egress,
        @Nullable CredentialSource credentials,
        boolean allowCredentialsOverPlainHttp,
        List<String> credentialOrigins) {

    /** Renders no setting, so a credential source or a default header never reaches a log line. */
    @Override
    public String toString() {
        return "ClientSettings";
    }
}
