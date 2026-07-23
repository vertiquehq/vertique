// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Typed per-client connection-pool override read from {@code restClient.{name}.pool}.
 *
 * <p>Every component is a nullable boxed value: an absent field means "not overridden" and falls
 * back to the builder-level pool baseline. Present values are validated at parse time so malformed
 * config fails fast at startup: {@code http1MaxSize >= 1}, {@code http2MaxSize >= 1},
 * {@code eventLoopSize >= 0}, and {@code maxLifetimeSeconds >= 0}. The {@code maxWaitQueueSize} and
 * {@code cleanerPeriodMs} fields are not bounds-validated (they pass through to Vert.x as-is).
 *
 * @param http1MaxSize the maximum HTTP/1.1 connections per endpoint ({@code >= 1}), or {@code null}
 *     when not overridden
 * @param http2MaxSize the maximum HTTP/2 connections per endpoint ({@code >= 1}), or {@code null}
 *     when not overridden
 * @param maxWaitQueueSize the maximum pending acquisition queue size (unvalidated), or {@code null}
 *     when not overridden
 * @param eventLoopSize the pool's event-loop size ({@code >= 0}), or {@code null} when not overridden
 * @param cleanerPeriodMs the pool-cleaner period in milliseconds (unvalidated), or {@code null} when
 *     not overridden
 * @param maxLifetimeSeconds the maximum connection lifetime in seconds ({@code >= 0}), or
 *     {@code null} when not overridden
 */
public record RestClientPoolConfig(
        Integer http1MaxSize,
        Integer http2MaxSize,
        Integer maxWaitQueueSize,
        Integer eventLoopSize,
        Integer cleanerPeriodMs,
        Integer maxLifetimeSeconds) {

    /**
     * Compact validator enforcing the per-field bounds for present values. Absent ({@code null})
     * fields and the unvalidated {@code maxWaitQueueSize}/{@code cleanerPeriodMs} are left untouched.
     *
     * @throws ConfigurationException if any present bounds-validated value is out of range
     */
    public RestClientPoolConfig {
        if (http1MaxSize != null && http1MaxSize < 1) {
            throw new ConfigurationException("restClient.<name>.pool.http1MaxSize must be >= 1, got " + http1MaxSize);
        }
        if (http2MaxSize != null && http2MaxSize < 1) {
            throw new ConfigurationException("restClient.<name>.pool.http2MaxSize must be >= 1, got " + http2MaxSize);
        }
        if (eventLoopSize != null && eventLoopSize < 0) {
            throw new ConfigurationException("restClient.<name>.pool.eventLoopSize must be >= 0, got " + eventLoopSize);
        }
        if (maxLifetimeSeconds != null && maxLifetimeSeconds < 0) {
            throw new ConfigurationException(
                    "restClient.<name>.pool.maxLifetimeSeconds must be >= 0, got " + maxLifetimeSeconds);
        }
    }
}
