// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Parsed {@code workflow.executor} configuration: how many executor instances a node runs and how each one claims and
 * runs recorded commands.
 *
 * @param instances      the verticle instances of the executor on this node, each on its own event loop; 1 to 64
 * @param concurrency    the segments one instance keeps in flight at a time; 1 to 256
 * @param pollIntervalMs the pause between two looks for runnable commands, in milliseconds; at least 100
 * @param leaseSeconds   how long a claim holds a command, in seconds; at least 5. Size it above the longest healthy
 *     segment: the execution lock is admitted until two margins before the lease ends and the segment's body is
 *     bounded one margin before it ends, where the margin is a tenth of the lease and at least one second
 */
public record WorkflowExecutorConfig(int instances, int concurrency, long pollIntervalMs, int leaseSeconds) {

    /** The default number of executor instances per node. */
    public static final int DEFAULT_INSTANCES = 1;

    /** The default number of segments one instance keeps in flight. */
    public static final int DEFAULT_CONCURRENCY = 8;

    /** The default pause between two looks for runnable commands, in milliseconds. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000L;

    /** The default claim lease, in seconds. */
    public static final int DEFAULT_LEASE_SECONDS = 60;

    /**
     * Validates the configuration.
     *
     * @throws ConfigurationException naming the offending key if a value is outside its bound
     */
    public WorkflowExecutorConfig {
        if (instances < 1 || instances > 64) {
            throw new ConfigurationException(
                    "workflow.executor.instances must be between 1 and 64 but was " + instances);
        }
        if (concurrency < 1 || concurrency > 256) {
            throw new ConfigurationException(
                    "workflow.executor.concurrency must be between 1 and 256 but was " + concurrency);
        }
        if (pollIntervalMs < 100) {
            throw new ConfigurationException(
                    "workflow.executor.pollIntervalMs must be at least 100 but was " + pollIntervalMs);
        }
        if (leaseSeconds < 5) {
            throw new ConfigurationException(
                    "workflow.executor.leaseSeconds must be at least 5 but was " + leaseSeconds);
        }
    }

    @JsonCreator
    static WorkflowExecutorConfig fromJson(
            @JsonProperty("instances") @Nullable Integer instances,
            @JsonProperty("concurrency") @Nullable Integer concurrency,
            @JsonProperty("pollIntervalMs") @Nullable Long pollIntervalMs,
            @JsonProperty("leaseSeconds") @Nullable Integer leaseSeconds) {
        return new WorkflowExecutorConfig(
                instances == null ? DEFAULT_INSTANCES : instances,
                concurrency == null ? DEFAULT_CONCURRENCY : concurrency,
                pollIntervalMs == null ? DEFAULT_POLL_INTERVAL_MS : pollIntervalMs,
                leaseSeconds == null ? DEFAULT_LEASE_SECONDS : leaseSeconds);
    }

    /**
     * Returns the configuration that applies when none is supplied.
     *
     * @return a configuration with every default
     */
    public static WorkflowExecutorConfig defaults() {
        return new WorkflowExecutorConfig(
                DEFAULT_INSTANCES, DEFAULT_CONCURRENCY, DEFAULT_POLL_INTERVAL_MS, DEFAULT_LEASE_SECONDS);
    }

    /**
     * The margin that the lease keeps in reserve: a tenth of the lease, and at least one second.
     *
     * @return the margin in milliseconds
     */
    public long marginMs() {
        return Math.max(leaseSeconds * 100L, 1000L);
    }
}
