// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Parsed {@code workflow.recording} configuration: how callers that change a workflow instance wait for it.
 *
 * @param lockTimeoutMs the longest time, in milliseconds, a caller waits to take an instance's execution lock before
 *     its transaction fails with a retryable persistence error; at least 1
 */
public record WorkflowRecordingConfig(long lockTimeoutMs) {

    /** The default wait for an instance's execution lock, in milliseconds. */
    public static final long DEFAULT_LOCK_TIMEOUT_MS = 5000L;

    /**
     * Validates the configuration.
     *
     * @throws ConfigurationException if {@code lockTimeoutMs} is less than 1
     */
    public WorkflowRecordingConfig {
        if (lockTimeoutMs < 1) {
            throw new ConfigurationException("workflow.recording.lockTimeoutMs must be at least 1");
        }
    }

    @JsonCreator
    static WorkflowRecordingConfig fromJson(@JsonProperty("lockTimeoutMs") @Nullable Long lockTimeoutMs) {
        return new WorkflowRecordingConfig(lockTimeoutMs == null ? DEFAULT_LOCK_TIMEOUT_MS : lockTimeoutMs);
    }

    /**
     * Returns the configuration that applies when none is supplied.
     *
     * @return a configuration with the default lock timeout
     */
    public static WorkflowRecordingConfig defaults() {
        return new WorkflowRecordingConfig(DEFAULT_LOCK_TIMEOUT_MS);
    }
}
