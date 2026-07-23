// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import jakarta.annotation.Nullable;

/**
 * Safe-by-type identity of an outbound REST-client call target. Carries only the route-level shape
 * — NEVER the expanded request URI, query string, or concrete path parameters.
 *
 * @param scheme       the target scheme (e.g. {@code "https"}), or {@code null} if undetermined
 * @param host         the target host, or {@code null} if undetermined
 * @param port         the target port, or {@code -1} for the scheme default / undetermined
 * @param pathTemplate the route template (e.g. {@code "/users/{id}"}), or {@code null} if
 *                     unavailable
 */
public record RestClientAttemptTarget(
        @Nullable String scheme,
        @Nullable String host,
        int port,
        @Nullable String pathTemplate) {}
