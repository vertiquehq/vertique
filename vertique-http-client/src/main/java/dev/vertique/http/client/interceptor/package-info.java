// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * The extension point of the governed HTTP client.
 *
 * <p>{@link dev.vertique.http.client.interceptor.HttpClientInterceptor} prepares every request before
 * credentials are applied and is told how every exchange ended. The records carry framework facts
 * only: no credential, no body and no {@code toString} that prints a header value, path or query.
 */
package dev.vertique.http.client.interceptor;
