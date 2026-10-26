// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Parsed {@code workflow.recording} configuration: execution-lock waits and encoded payload bounds.
 *
 * @param lockTimeoutMs the longest time, in milliseconds, a caller waits to take an instance's execution lock before
 *     its transaction fails with a retryable persistence error; at least 1
 * @param maxPayloadBytes the maximum encoded JSON payload size in bytes; at least 1024
 */
public record WorkflowRecordingConfig(long lockTimeoutMs, int maxPayloadBytes) {

    /** The default wait for an instance's execution lock, in milliseconds. */
    public static final long DEFAULT_LOCK_TIMEOUT_MS = 5000L;

    /** The default maximum encoded JSON payload size, in bytes. */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 262144;

    /**
     * Validates the configuration.
     *
     * @throws ConfigurationException if {@code lockTimeoutMs} is less than 1 or {@code maxPayloadBytes} is less than 1024
     */
    public WorkflowRecordingConfig {
        if (lockTimeoutMs < 1) {
            throw new ConfigurationException("workflow.recording.lockTimeoutMs must be at least 1");
        }
        if (maxPayloadBytes < 1024) {
            throw new ConfigurationException("workflow.recording.maxPayloadBytes must be at least 1024");
        }
    }

    /**
     * Creates a configuration with the default maximum payload size.
     *
     * @param lockTimeoutMs the execution-lock wait in milliseconds; at least 1
     * @throws ConfigurationException if {@code lockTimeoutMs} is less than 1
     */
    public WorkflowRecordingConfig(long lockTimeoutMs) {
        this(lockTimeoutMs, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    @JsonCreator
    static WorkflowRecordingConfig fromJson(
            @JsonProperty("lockTimeoutMs") @Nullable Long lockTimeoutMs,
            @JsonProperty("maxPayloadBytes") @Nullable Integer maxPayloadBytes) {
        return new WorkflowRecordingConfig(
                lockTimeoutMs == null ? DEFAULT_LOCK_TIMEOUT_MS : lockTimeoutMs,
                maxPayloadBytes == null ? DEFAULT_MAX_PAYLOAD_BYTES : maxPayloadBytes);
    }

    /**
     * Returns the configuration that applies when none is supplied.
     *
     * @return a configuration with the default lock timeout and maximum payload size
     */
    public static WorkflowRecordingConfig defaults() {
        return new WorkflowRecordingConfig(DEFAULT_LOCK_TIMEOUT_MS, DEFAULT_MAX_PAYLOAD_BYTES);
    }
}
