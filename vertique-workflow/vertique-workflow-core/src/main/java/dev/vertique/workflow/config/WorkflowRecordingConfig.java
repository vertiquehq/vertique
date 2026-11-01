// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Parsed {@code workflow.recording} configuration: execution-lock waits, encoded payload bounds, the open-signal limit,
 * the size of the compiled-schema cache and the limit on intake from outside.
 *
 * @param lockTimeoutMs the longest time, in milliseconds, a caller waits to take an instance's execution lock before
 *     its transaction fails with a retryable persistence error; at least 1
 * @param maxPayloadBytes the maximum encoded JSON payload size in bytes; at least 1024
 * @param maxOpenSignalsPerInstance the most signals an instance may hold open (pending, deferred, claimed or parked)
 *     before recording another is refused; from 1 to 10000
 * @param schemaCacheMaxEntries the most compiled payload schemas the record-time validation keeps, keyed by schema
 *     hash; at least 1
 * @param intake the limit on starts and signals from outside while many commands are due; see
 *     {@link WorkflowIntakeConfig}; {@code null} means {@link WorkflowIntakeConfig#defaults()}
 */
public record WorkflowRecordingConfig(
        long lockTimeoutMs,
        int maxPayloadBytes,
        int maxOpenSignalsPerInstance,
        int schemaCacheMaxEntries,
        WorkflowIntakeConfig intake) {

    /** The default wait for an instance's execution lock, in milliseconds. */
    public static final long DEFAULT_LOCK_TIMEOUT_MS = 5000L;

    /** The default maximum encoded JSON payload size, in bytes. */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 262144;

    /** The default maximum number of open signals per instance. */
    public static final int DEFAULT_MAX_OPEN_SIGNALS_PER_INSTANCE = 100;

    /** The default maximum number of compiled payload schemas kept by record-time validation. */
    public static final int DEFAULT_SCHEMA_CACHE_MAX_ENTRIES = 256;

    /** The largest accepted maximum number of open signals per instance. */
    public static final int MAX_OPEN_SIGNALS_PER_INSTANCE_LIMIT = 10000;

    /**
     * Validates the configuration.
     *
     * @throws ConfigurationException if {@code lockTimeoutMs} is less than 1, {@code maxPayloadBytes} is less than 1024
     *     {@code maxOpenSignalsPerInstance} is outside 1 to 10000 or {@code schemaCacheMaxEntries} is less than 1
     */
    public WorkflowRecordingConfig {
        if (lockTimeoutMs < 1) {
            throw new ConfigurationException("workflow.recording.lockTimeoutMs must be at least 1");
        }
        if (maxPayloadBytes < 1024) {
            throw new ConfigurationException("workflow.recording.maxPayloadBytes must be at least 1024");
        }
        if (maxOpenSignalsPerInstance < 1 || maxOpenSignalsPerInstance > MAX_OPEN_SIGNALS_PER_INSTANCE_LIMIT) {
            throw new ConfigurationException(
                    "workflow.recording.maxOpenSignalsPerInstance must be between 1 and 10000");
        }
        if (schemaCacheMaxEntries < 1) {
            throw new ConfigurationException("workflow.recording.schemaCacheMaxEntries must be at least 1");
        }
        if (intake == null) {
            intake = WorkflowIntakeConfig.defaults();
        }
    }

    /**
     * Creates a configuration with the default intake limit.
     *
     * @param lockTimeoutMs the execution-lock wait in milliseconds; at least 1
     * @param maxPayloadBytes the maximum encoded JSON payload size in bytes; at least 1024
     * @param maxOpenSignalsPerInstance the most signals an instance may hold open; from 1 to 10000
     * @param schemaCacheMaxEntries the most compiled payload schemas kept by record-time validation; at least 1
     * @throws ConfigurationException if a bound is violated
     */
    public WorkflowRecordingConfig(
            long lockTimeoutMs, int maxPayloadBytes, int maxOpenSignalsPerInstance, int schemaCacheMaxEntries) {
        this(
                lockTimeoutMs,
                maxPayloadBytes,
                maxOpenSignalsPerInstance,
                schemaCacheMaxEntries,
                WorkflowIntakeConfig.defaults());
    }

    /**
     * Creates a configuration with the default schema cache size.
     *
     * @param lockTimeoutMs the execution-lock wait in milliseconds; at least 1
     * @param maxPayloadBytes the maximum encoded JSON payload size in bytes; at least 1024
     * @param maxOpenSignalsPerInstance the most signals an instance may hold open; from 1 to 10000
     * @throws ConfigurationException if a bound is violated
     */
    public WorkflowRecordingConfig(long lockTimeoutMs, int maxPayloadBytes, int maxOpenSignalsPerInstance) {
        this(lockTimeoutMs, maxPayloadBytes, maxOpenSignalsPerInstance, DEFAULT_SCHEMA_CACHE_MAX_ENTRIES);
    }

    /**
     * Creates a configuration with the default maximum number of open signals.
     *
     * @param lockTimeoutMs the execution-lock wait in milliseconds; at least 1
     * @param maxPayloadBytes the maximum encoded JSON payload size in bytes; at least 1024
     * @throws ConfigurationException if a bound is violated
     */
    public WorkflowRecordingConfig(long lockTimeoutMs, int maxPayloadBytes) {
        this(lockTimeoutMs, maxPayloadBytes, DEFAULT_MAX_OPEN_SIGNALS_PER_INSTANCE);
    }

    /**
     * Creates a configuration with the default maximum payload size.
     *
     * @param lockTimeoutMs the execution-lock wait in milliseconds; at least 1
     * @throws ConfigurationException if {@code lockTimeoutMs} is less than 1
     */
    public WorkflowRecordingConfig(long lockTimeoutMs) {
        this(lockTimeoutMs, DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_OPEN_SIGNALS_PER_INSTANCE);
    }

    @JsonCreator
    static WorkflowRecordingConfig fromJson(
            @JsonProperty("lockTimeoutMs") @Nullable Long lockTimeoutMs,
            @JsonProperty("maxPayloadBytes") @Nullable Integer maxPayloadBytes,
            @JsonProperty("maxOpenSignalsPerInstance") @Nullable Integer maxOpenSignalsPerInstance,
            @JsonProperty("schemaCacheMaxEntries") @Nullable Integer schemaCacheMaxEntries,
            @JsonProperty("intake") @Nullable WorkflowIntakeConfig intake) {
        return new WorkflowRecordingConfig(
                lockTimeoutMs == null ? DEFAULT_LOCK_TIMEOUT_MS : lockTimeoutMs,
                maxPayloadBytes == null ? DEFAULT_MAX_PAYLOAD_BYTES : maxPayloadBytes,
                maxOpenSignalsPerInstance == null ? DEFAULT_MAX_OPEN_SIGNALS_PER_INSTANCE : maxOpenSignalsPerInstance,
                schemaCacheMaxEntries == null ? DEFAULT_SCHEMA_CACHE_MAX_ENTRIES : schemaCacheMaxEntries,
                intake == null ? WorkflowIntakeConfig.defaults() : intake);
    }

    /**
     * Returns the configuration that applies when none is supplied.
     *
     * @return a configuration with the default lock timeout, maximum payload size, open-signal limit, schema cache
     *     size and intake limit
     */
    public static WorkflowRecordingConfig defaults() {
        return new WorkflowRecordingConfig(
                DEFAULT_LOCK_TIMEOUT_MS,
                DEFAULT_MAX_PAYLOAD_BYTES,
                DEFAULT_MAX_OPEN_SIGNALS_PER_INSTANCE,
                DEFAULT_SCHEMA_CACHE_MAX_ENTRIES,
                WorkflowIntakeConfig.defaults());
    }
}
