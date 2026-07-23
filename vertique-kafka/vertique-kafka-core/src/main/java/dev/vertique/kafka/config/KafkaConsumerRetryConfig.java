// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * Typed retry configuration for a Kafka consumer, read from
 * {@code kafka.consumers.{name}.retry}.
 *
 * <p>Follows the canonical config-record pattern: a {@link JsonCreator} factory fills defaults for
 * omitted properties from {@link #defaults()}. All components are boxed and non-null after
 * construction (defaults applied), so callers never see {@code null} retry parameters.
 *
 * @param maxRetries the maximum number of retry attempts before the exhausted strategy applies
 *     (default {@code 3})
 * @param backoffMs the base backoff delay between retries in milliseconds (default {@code 1000})
 * @param backoffMultiplier the multiplier applied to the backoff delay after each retry
 *     (default {@code 2.0})
 * @param maxBackoffMs the maximum backoff delay cap in milliseconds (default {@code 60000})
 * @param exhaustedStrategy the strategy applied once retries are exhausted (default
 *     {@code "DEAD_LETTER"})
 */
public record KafkaConsumerRetryConfig(
        Integer maxRetries, Long backoffMs, Double backoffMultiplier, Long maxBackoffMs, String exhaustedStrategy) {

    /**
     * Jackson factory filling defaults for omitted properties from {@link #defaults()}.
     *
     * @param maxRetries the max retry attempts; defaults to {@code 3} when {@code null}
     * @param backoffMs the base backoff in ms; defaults to {@code 1000} when {@code null}
     * @param backoffMultiplier the backoff multiplier; defaults to {@code 2.0} when {@code null}
     * @param maxBackoffMs the backoff cap in ms; defaults to {@code 60000} when {@code null}
     * @param exhaustedStrategy the exhausted strategy; defaults to {@code "DEAD_LETTER"} when
     *     {@code null}
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static KafkaConsumerRetryConfig fromJson(
            @JsonProperty("maxRetries") @Nullable Integer maxRetries,
            @JsonProperty("backoffMs") @Nullable Long backoffMs,
            @JsonProperty("backoffMultiplier") @Nullable Double backoffMultiplier,
            @JsonProperty("maxBackoffMs") @Nullable Long maxBackoffMs,
            @JsonProperty("exhaustedStrategy") @Nullable String exhaustedStrategy) {
        KafkaConsumerRetryConfig d = defaults();
        return new KafkaConsumerRetryConfig(
                maxRetries != null ? maxRetries : d.maxRetries,
                backoffMs != null ? backoffMs : d.backoffMs,
                backoffMultiplier != null ? backoffMultiplier : d.backoffMultiplier,
                maxBackoffMs != null ? maxBackoffMs : d.maxBackoffMs,
                exhaustedStrategy != null ? exhaustedStrategy : d.exhaustedStrategy);
    }

    /**
     * Default retry configuration: {@code maxRetries=3}, {@code backoffMs=1000},
     * {@code backoffMultiplier=2.0}, {@code maxBackoffMs=60000}, {@code exhaustedStrategy=DEAD_LETTER}.
     *
     * @return the default config; never {@code null}
     */
    public static KafkaConsumerRetryConfig defaults() {
        return new KafkaConsumerRetryConfig(3, 1000L, 2.0, 60_000L, "DEAD_LETTER");
    }
}
