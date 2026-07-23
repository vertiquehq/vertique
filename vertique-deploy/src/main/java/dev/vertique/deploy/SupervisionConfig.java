// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Supervision configuration controlling restart budget and backoff behavior.
 *
 * @param maxRestarts maximum number of restarts allowed within the time window
 * @param withinMs sliding window duration in milliseconds
 * @param initialBackoffMs initial delay before the first restart attempt in milliseconds
 * @param maxBackoffMs maximum delay cap for exponential backoff in milliseconds
 */
public record SupervisionConfig(int maxRestarts, long withinMs, long initialBackoffMs, long maxBackoffMs) {

    /** Default values: 5 restarts within 60s, 1s initial / 30s max backoff. */
    public static final SupervisionConfig DEFAULT = new SupervisionConfig(5, 60_000L, 1_000L, 30_000L);

    /**
     * Jackson factory that fills each omitted property from {@link #DEFAULT}, so a partial
     * supervision JSON object (e.g. {@code {maxRestarts: 9}}) keeps default values for the fields it
     * does not set. This preserves the per-field defaulting that the services layer applied before
     * supervision config was parsed into this record.
     *
     * @param maxRestarts max restarts; defaults to {@link #DEFAULT} when {@code null}
     * @param withinMs sliding window in ms; defaults to {@link #DEFAULT} when {@code null}
     * @param initialBackoffMs initial backoff in ms; defaults to {@link #DEFAULT} when {@code null}
     * @param maxBackoffMs max backoff in ms; defaults to {@link #DEFAULT} when {@code null}
     * @return the deserialized config with defaults applied for omitted fields
     */
    @JsonCreator
    static SupervisionConfig fromJson(
            @JsonProperty("maxRestarts") Integer maxRestarts,
            @JsonProperty("withinMs") Long withinMs,
            @JsonProperty("initialBackoffMs") Long initialBackoffMs,
            @JsonProperty("maxBackoffMs") Long maxBackoffMs) {
        return new SupervisionConfig(
                maxRestarts != null ? maxRestarts : DEFAULT.maxRestarts,
                withinMs != null ? withinMs : DEFAULT.withinMs,
                initialBackoffMs != null ? initialBackoffMs : DEFAULT.initialBackoffMs,
                maxBackoffMs != null ? maxBackoffMs : DEFAULT.maxBackoffMs);
    }
}
