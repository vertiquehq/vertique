// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.config;

import jakarta.annotation.Nullable;

/**
 * Connection pool override read from {@code httpClient.{name}.pool}. An absent component means the
 * builder's pool options (or the Vert.x default) apply. {@link HttpClientConfig} validates the
 * bounds: {@code http1MaxSize} and {@code http2MaxSize} at least 1, {@code eventLoopSize} and {@code
 * maxLifetimeSeconds} not negative.
 *
 * @param http1MaxSize the most HTTP/1.1 connections per endpoint, or {@code null}
 * @param http2MaxSize the most HTTP/2 connections per endpoint, or {@code null}
 * @param maxWaitQueueSize the most requests waiting for a connection, or {@code null}
 * @param eventLoopSize the number of event loops the pool uses, or {@code null}
 * @param cleanerPeriodMs the pool cleaner period in milliseconds, or {@code null}
 * @param maxLifetimeSeconds the longest connection lifetime in seconds, or {@code null}
 */
public record HttpClientPoolConfig(
        @Nullable Integer http1MaxSize,
        @Nullable Integer http2MaxSize,
        @Nullable Integer maxWaitQueueSize,
        @Nullable Integer eventLoopSize,
        @Nullable Integer cleanerPeriodMs,
        @Nullable Integer maxLifetimeSeconds) {}
