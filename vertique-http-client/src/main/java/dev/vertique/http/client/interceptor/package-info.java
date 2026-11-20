// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * The extension point of the governed HTTP client.
 *
 * <p>{@link dev.vertique.http.client.interceptor.HttpClientInterceptor} prepares every request before
 * credentials are applied and is told how every exchange ended. The records are not redacted: the
 * completion snapshot holds the credential headers, the response head holds every response header including
 * {@code Set-Cookie}, and, when the client captures bodies, the completion holds
 * both bodies. No {@code toString} prints a header value, a body, a path or a query.
 */
package dev.vertique.http.client.interceptor;
