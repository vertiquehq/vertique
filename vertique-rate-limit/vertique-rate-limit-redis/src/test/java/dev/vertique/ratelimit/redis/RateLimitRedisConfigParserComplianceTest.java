// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 5 (redis portion): {@code
 * RateLimitRedisModule#clusteredRateLimitBackend} read {@code rateLimit.redis.*} via {@link
 * RateLimitRedisConfig#fromJson}'s raw {@code JsonObject} scalar reads ({@code
 * redis.getLong("operationTimeoutMs")}) instead of the canonical injected {@code
 * dev.vertique.core.config.ConfigParser} (docs/standards/config.md rule R10). Vert.x's {@code
 * JsonObject#getLong} throws a raw {@link ClassCastException} when the underlying value is a
 * coercible {@link String} (e.g. {@code "operationTimeoutMs": "200"}), unlike {@code
 * ConfigParser}'s dedicated, coercion-lenient mapper.
 */
class RateLimitRedisConfigParserComplianceTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @Test
    void shouldCoerceStringEncodedRedisScalarConfigThroughTheCanonicalParser() {
        JsonObject redis = new JsonObject()
                .put("connection", "primary")
                .put("namespace", "rl")
                .put("operationTimeoutMs", "200")
                .put("expirationSlackMs", "1000");
        JsonObject config = new JsonObject().put("rateLimit", new JsonObject().put("redis", redis));

        // vertx/clients are null: config resolution must fully succeed (no ClassCastException) and
        // reach the point where the shared client registry is dereferenced -- a NullPointerException
        // there is expected and proves config parsing itself never threw.
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> RateLimitRedisModule.clusteredRateLimitBackend(config, PARSER, null, null));

        assertThat(thrown)
                .as("string-encoded rateLimit.redis.* scalars must coerce through the canonical ConfigParser -- "
                        + "reaching the null RedisClientRegistry dereference (NPE), never a raw ClassCastException "
                        + "from config parsing itself")
                .isInstanceOf(NullPointerException.class);
    }
}
