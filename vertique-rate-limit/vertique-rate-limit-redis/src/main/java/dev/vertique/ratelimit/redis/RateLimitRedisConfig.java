// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;

/**
 * Typed {@code rateLimit.redis.*} configuration (contracts/rate-limit-runtime.md, "Configuration").
 * Every component is required with no default and eagerly bounds-validated by this record itself —
 * the same "required, no default" idiom {@link dev.vertique.ratelimit.RateLimitPolicy} follows
 * ({@link #fromJson} detects an omitted primitive before it loses that distinction, and this
 * record's compact constructor enforces every bound), not a claim deferred to config validation
 * elsewhere.
 *
 * @param connection {@code redis.connections.<name>} profile name
 * @param namespace prefixes the physical Redis key
 * @param operationTimeoutMs the Vertique-owned deadline bounding one clustered {@code consume(...)};
 *     bounded {@code 1..60_000} ms
 * @param expirationSlackMs added to time-to-full when computing the Vertique-issued {@code PEXPIRE}
 *     bound; bounded {@code 0..86_400_000} ms
 */
record RateLimitRedisConfig(String connection, String namespace, long operationTimeoutMs, long expirationSlackMs) {

    private static final long MIN_OPERATION_TIMEOUT_MS = 1L;
    private static final long MAX_OPERATION_TIMEOUT_MS = 60_000L;
    private static final long MIN_EXPIRATION_SLACK_MS = 0L;
    private static final long MAX_EXPIRATION_SLACK_MS = 86_400_000L;

    /**
     * Compact constructor — validates every component.
     *
     * @throws ConfigurationException if {@code connection}/{@code namespace} is {@code null}, or
     *     {@code operationTimeoutMs}/{@code expirationSlackMs} is out of bounds
     */
    RateLimitRedisConfig {
        if (connection == null) {
            throw new ConfigurationException("rateLimit.redis.connection is required (no default)");
        }
        if (namespace == null) {
            throw new ConfigurationException("rateLimit.redis.namespace is required (no default)");
        }
        if (operationTimeoutMs < MIN_OPERATION_TIMEOUT_MS || operationTimeoutMs > MAX_OPERATION_TIMEOUT_MS) {
            throw new ConfigurationException("rateLimit.redis.operationTimeoutMs must be between "
                    + MIN_OPERATION_TIMEOUT_MS + " and " + MAX_OPERATION_TIMEOUT_MS + ", got " + operationTimeoutMs);
        }
        if (expirationSlackMs < MIN_EXPIRATION_SLACK_MS || expirationSlackMs > MAX_EXPIRATION_SLACK_MS) {
            throw new ConfigurationException("rateLimit.redis.expirationSlackMs must be between "
                    + MIN_EXPIRATION_SLACK_MS + " and " + MAX_EXPIRATION_SLACK_MS + ", got " + expirationSlackMs);
        }
    }

    /**
     * Jackson-free factory over the raw {@code rateLimit.redis} section, mirroring {@link
     * dev.vertique.ratelimit.RateLimitPolicy#fromJson}: {@code operationTimeoutMs}/{@code
     * expirationSlackMs} are read as boxed {@link Long} so an omitted value fails loudly ("required,
     * no default") instead of silently coercing to {@code 0} before the compact constructor ever
     * sees it.
     *
     * @param redis the raw {@code rateLimit.redis} configuration section
     * @return the resolved, validated config
     * @throws ConfigurationException if {@code operationTimeoutMs}/{@code expirationSlackMs} is
     *     omitted, or any component fails the compact constructor's own validation
     */
    static RateLimitRedisConfig fromJson(JsonObject redis) {
        Long operationTimeoutMs = redis.getLong("operationTimeoutMs");
        if (operationTimeoutMs == null) {
            throw new ConfigurationException("rateLimit.redis.operationTimeoutMs is required (no default)");
        }
        Long expirationSlackMs = redis.getLong("expirationSlackMs");
        if (expirationSlackMs == null) {
            throw new ConfigurationException("rateLimit.redis.expirationSlackMs is required (no default)");
        }
        return new RateLimitRedisConfig(
                redis.getString("connection"), redis.getString("namespace"), operationTimeoutMs, expirationSlackMs);
    }
}
