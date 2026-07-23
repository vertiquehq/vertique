// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.job;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Typed configuration for the simulated job progress pipeline.
 *
 * <p>Deserialized from the {@code "job"} section of the application config:
 *
 * <pre>{@code
 * {
 *   "job": {
 *     "stepIntervalMs": 50,
 *     "stepCount": 5
 *   }
 * }
 * }</pre>
 *
 * @param stepIntervalMs milliseconds between simulated progress ticks; defaults to {@code 50}
 * @param stepCount total number of ticks before the job reaches {@link JobStatus#DONE}; defaults
 *     to {@code 5}
 */
public record JobConfig(long stepIntervalMs, int stepCount) {

    /** Default configuration: 50 ms interval, 5 steps. */
    public static final JobConfig DEFAULT = new JobConfig(50L, 5);

    /**
     * Jackson factory that fills each omitted property from {@link #DEFAULT}, so a partial or
     * absent {@code job} section keeps default values for the fields it does not set.
     *
     * @param stepIntervalMs milliseconds between ticks; defaults to {@link JobConfig#DEFAULT} when
     *     {@code null}
     * @param stepCount total ticks to completion; defaults to {@link JobConfig#DEFAULT} when
     *     {@code null}
     * @return the deserialized config with defaults applied for omitted fields
     */
    @JsonCreator
    static JobConfig fromJson(
            @JsonProperty("stepIntervalMs") Long stepIntervalMs, @JsonProperty("stepCount") Integer stepCount) {
        return new JobConfig(
                stepIntervalMs != null ? stepIntervalMs : DEFAULT.stepIntervalMs,
                stepCount != null ? stepCount : DEFAULT.stepCount);
    }
}
