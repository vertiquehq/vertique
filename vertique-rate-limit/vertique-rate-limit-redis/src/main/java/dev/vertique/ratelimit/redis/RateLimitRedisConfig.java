// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import java.util.Objects;

/**
 * Typed {@code rateLimit.redis.*} configuration (contracts/rate-limit-runtime.md, "Configuration").
 * Bounds ({@code plan.md} § Bounds — timeout {@code 1..60_000} ms, slack {@code 0..86_400_000} ms)
 * are enforced by startup config validation upstream (T003/T004's ownership); this record does not
 * re-validate them, matching this task's "Task readiness" scope note.
 *
 * @param connection {@code redis.connections.<name>} profile name
 * @param namespace prefixes the physical Redis key
 * @param operationTimeoutMs the Vertique-owned deadline bounding one clustered {@code consume(...)}
 * @param expirationSlackMs added to time-to-full when computing the Vertique-issued {@code PEXPIRE} bound
 */
record RateLimitRedisConfig(String connection, String namespace, long operationTimeoutMs, long expirationSlackMs) {
    RateLimitRedisConfig {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(namespace, "namespace");
    }
}
