// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P02/P03 review repair (T017, item 1): {@link RateLimitRedisModule#clusteredRateLimitBackend} —
 * and, underneath it, {@link RateLimitRedisConfig} — eagerly validates {@code rateLimit.redis.*}
 * at this provider's own construction, rather than silently defaulting an omitted {@code
 * operationTimeoutMs}/{@code expirationSlackMs} to {@code 0L} (which previously built a 1 ms-
 * deadline backend instead of failing startup). {@code vertx}/{@code clients} are never
 * dereferenced before this validation runs, so every row below passes {@code null} for both.
 */
class RateLimitRedisModuleEagerValidationTest {

    private static final JsonObject VALID_REDIS_SECTION = new JsonObject()
            .put("connection", "primary")
            .put("namespace", "rl")
            .put("operationTimeoutMs", 2_000)
            .put("expirationSlackMs", 1_000);

    static Stream<MatrixRow> validationMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldFailWhenOperationTimeoutMsIsOmitted",
                        redis -> removed(redis, "operationTimeoutMs"),
                        "operationTimeoutMs"),
                new MatrixRow(
                        "shouldFailWhenOperationTimeoutMsIsBelowOne",
                        redis -> redis.copy().put("operationTimeoutMs", 0),
                        "operationTimeoutMs"),
                new MatrixRow(
                        "shouldFailWhenOperationTimeoutMsExceeds60000",
                        redis -> redis.copy().put("operationTimeoutMs", 60_001),
                        "operationTimeoutMs"),
                new MatrixRow(
                        "shouldFailWhenExpirationSlackMsIsOmitted",
                        redis -> removed(redis, "expirationSlackMs"),
                        "expirationSlackMs"),
                new MatrixRow(
                        "shouldFailWhenExpirationSlackMsIsNegative",
                        redis -> redis.copy().put("expirationSlackMs", -1),
                        "expirationSlackMs"),
                new MatrixRow(
                        "shouldFailWhenExpirationSlackMsExceeds86400000",
                        redis -> redis.copy().put("expirationSlackMs", 86_400_001L),
                        "expirationSlackMs"),
                new MatrixRow("shouldFailWhenConnectionIsAbsent", redis -> removed(redis, "connection"), "connection"),
                new MatrixRow("shouldFailWhenNamespaceIsAbsent", redis -> removed(redis, "namespace"), "namespace"));
    }

    private static JsonObject removed(JsonObject redis, String key) {
        JsonObject copy = redis.copy();
        copy.remove(key);
        return copy;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationMatrix")
    @DisplayName("fails eagerly on an invalid rateLimit.redis.* configuration")
    void shouldFailEagerlyOnInvalidRedisConfig(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    private static void assertConfigurationExceptionFor(UnaryOperator<JsonObject> mutation, String expectedFragment) {
        JsonObject redis = mutation.apply(VALID_REDIS_SECTION);
        JsonObject config = new JsonObject().put("rateLimit", new JsonObject().put("redis", redis));

        assertThatThrownBy(() -> RateLimitRedisModule.clusteredRateLimitBackend(config, null, null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(expectedFragment);
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, UnaryOperator<JsonObject> mutation, String expectedFragment) {

        Executable proof() {
            return () -> assertConfigurationExceptionFor(mutation, expectedFragment);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
