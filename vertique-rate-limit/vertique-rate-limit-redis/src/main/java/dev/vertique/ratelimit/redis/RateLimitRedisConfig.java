// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
     * @throws ConfigurationException if {@code connection}/{@code namespace} is {@code null} or
     *     blank, or {@code operationTimeoutMs}/{@code expirationSlackMs} is out of bounds
     */
    RateLimitRedisConfig {
        if (connection == null || connection.isBlank()) {
            throw new ConfigurationException(
                    "rateLimit.redis.connection is required (no default) and must not be blank");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new ConfigurationException(
                    "rateLimit.redis.namespace is required (no default) and must not be blank");
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
     * Jackson-friendly factory over the raw {@code rateLimit.redis} section, mirroring {@link
     * dev.vertique.ratelimit.RateLimitPolicy#fromJson}: {@code operationTimeoutMs}/{@code
     * expirationSlackMs} are bound as boxed {@link Long} so an omitted value fails loudly
     * ("required, no default") instead of silently coercing to {@code 0} before the compact
     * constructor ever sees it. Invoked through the canonical injected {@code
     * dev.vertique.core.config.ConfigParser} (docs/standards/config.md rule R10) — never {@link
     * JsonObject#mapTo} or a hand-rolled reader — so a string-encoded value (e.g. {@code
     * "operationTimeoutMs": "200"}) coerces reliably through the parser's dedicated,
     * coercion-lenient mapper.
     *
     * @param connection the raw {@code rateLimit.redis.connection}, or {@code null} when omitted
     * @param namespace the raw {@code rateLimit.redis.namespace}, or {@code null} when omitted
     * @param operationTimeoutMs the raw {@code rateLimit.redis.operationTimeoutMs}, or {@code null}
     *     when omitted
     * @param expirationSlackMs the raw {@code rateLimit.redis.expirationSlackMs}, or {@code null}
     *     when omitted
     * @return the resolved, validated config
     * @throws ConfigurationException if {@code operationTimeoutMs}/{@code expirationSlackMs} is
     *     omitted, or any component fails the compact constructor's own validation
     */
    @JsonCreator
    static RateLimitRedisConfig fromJson(
            @JsonProperty("connection") String connection,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("operationTimeoutMs") Long operationTimeoutMs,
            @JsonProperty("expirationSlackMs") Long expirationSlackMs) {
        if (operationTimeoutMs == null) {
            throw new ConfigurationException("rateLimit.redis.operationTimeoutMs is required (no default)");
        }
        if (expirationSlackMs == null) {
            throw new ConfigurationException("rateLimit.redis.expirationSlackMs is required (no default)");
        }
        return new RateLimitRedisConfig(connection, namespace, operationTimeoutMs, expirationSlackMs);
    }
}
