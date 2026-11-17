// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import java.util.Map;

/**
 * The settings of one built client that the governance interceptors and the delegate read.
 *
 * @param basePath the path prefix of relative requests, empty when the base URL has none
 * @param hasBaseUrl whether the client has a base URL
 * @param validateHeaders whether the request header rules apply
 * @param defaultHeaders headers added to every request that does not set them
 * @param readTimeoutMs the default attempt timeout in milliseconds, 0 for none
 * @param maxRequestBytes the most request body bytes sent, 0 for no bound
 * @param maxResponseBytes the most response body bytes read, 0 for no bound
 */
record ClientSettings(
        String basePath,
        boolean hasBaseUrl,
        boolean validateHeaders,
        Map<String, String> defaultHeaders,
        long readTimeoutMs,
        long maxRequestBytes,
        long maxResponseBytes) {}
