// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import jakarta.annotation.Nullable;

/**
 * The final hop an exchange went to: scheme, host and port only, never a path, a query or user
 * information.
 *
 * @param scheme the scheme, or {@code null} when it was not known
 * @param host the host, or {@code null} when it was not known
 * @param port the port, or {@code -1} when it was not known
 */
public record HttpClientAttemptTarget(
        @Nullable String scheme, @Nullable String host, int port) {}
