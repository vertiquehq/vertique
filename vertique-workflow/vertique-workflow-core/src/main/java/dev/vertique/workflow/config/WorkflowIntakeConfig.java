// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Parsed {@code workflow.recording.intake} configuration: the limit on starts and signals that arrive from outside
 * while the cluster holds more due commands than its executors have run.
 *
 * <p>Every node counts the commands that are {@code PENDING} or {@code DEFERRED} and due, once per
 * {@code sampleIntervalMs}, and decides alone. It refuses new starts and signals from a reading at or above
 * {@code highMark} and admits them again from a reading at or below {@code lowMark}. The count is bounded at
 * {@code highMark + 1}, so a reading costs at most that many index entries however deep the queue is.
 *
 * <p>Because nodes sample on their own clocks, the limit is approximate: the commands recorded between the last
 * reading that admitted and the first that refuses can exceed {@code highMark}. The worst case is
 * {@code 5 x sampleIntervalMs} of the cluster's intake rate: the longest delay between two samples of an idle node,
 * which is four intervals, plus the interval a read may take.
 *
 * @param enabled whether the node samples and refuses; {@code false} runs no sampler and admits every call
 * @param highMark the number of due commands at or above which intake is refused; at least 2
 * @param lowMark the number of due commands at or below which intake is admitted again; at least 1 and less than
 *     {@code highMark}
 * @param sampleIntervalMs the time between two samples while the queue is above the low mark, and the longest a read
 *     may take, in milliseconds; at least 100
 */
public record WorkflowIntakeConfig(boolean enabled, int highMark, int lowMark, long sampleIntervalMs) {

    /** Whether the limit is on by default. */
    public static final boolean DEFAULT_ENABLED = true;

    /** The default number of due commands at or above which intake is refused. */
    public static final int DEFAULT_HIGH_MARK = 10_000;

    /** The default number of due commands at or below which intake is admitted again. */
    public static final int DEFAULT_LOW_MARK = 5_000;

    /** The default time between two samples, in milliseconds. */
    public static final long DEFAULT_SAMPLE_INTERVAL_MS = 1_000L;

    /** The shortest accepted time between two samples, in milliseconds. */
    public static final long MIN_SAMPLE_INTERVAL_MS = 100L;

    /**
     * Validates the configuration.
     *
     * @throws ConfigurationException if {@code lowMark} is less than 1, {@code lowMark} is not less than
     *     {@code highMark}, or {@code sampleIntervalMs} is less than 100
     */
    public WorkflowIntakeConfig {
        if (lowMark < 1) {
            throw new ConfigurationException("workflow.recording.intake.lowMark must be at least 1");
        }
        if (lowMark >= highMark) {
            throw new ConfigurationException(
                    "workflow.recording.intake.lowMark must be less than workflow.recording.intake.highMark");
        }
        if (sampleIntervalMs < MIN_SAMPLE_INTERVAL_MS) {
            throw new ConfigurationException(
                    "workflow.recording.intake.sampleIntervalMs must be at least " + MIN_SAMPLE_INTERVAL_MS);
        }
    }

    @JsonCreator
    static WorkflowIntakeConfig fromJson(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("highMark") @Nullable Integer highMark,
            @JsonProperty("lowMark") @Nullable Integer lowMark,
            @JsonProperty("sampleIntervalMs") @Nullable Long sampleIntervalMs) {
        return new WorkflowIntakeConfig(
                enabled == null ? DEFAULT_ENABLED : enabled,
                highMark == null ? DEFAULT_HIGH_MARK : highMark,
                lowMark == null ? DEFAULT_LOW_MARK : lowMark,
                sampleIntervalMs == null ? DEFAULT_SAMPLE_INTERVAL_MS : sampleIntervalMs);
    }

    /**
     * Returns the configuration that applies when none is supplied.
     *
     * @return the limit on, with a high mark of 10,000, a low mark of 5,000 and a sample interval of one second
     */
    public static WorkflowIntakeConfig defaults() {
        return new WorkflowIntakeConfig(
                DEFAULT_ENABLED, DEFAULT_HIGH_MARK, DEFAULT_LOW_MARK, DEFAULT_SAMPLE_INTERVAL_MS);
    }
}
