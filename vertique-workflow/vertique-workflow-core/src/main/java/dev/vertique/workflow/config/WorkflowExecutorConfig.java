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
 * @param maxAttempts    the claims that may end by a crash or a lease overrun before a command is dead-lettered; at
 *     least 1. A transient failure, a busy lock and a release never spend an attempt
 * @param transientBackoffBaseMs the pause before the first retry of a transient failure, in milliseconds; at least 1.
 *     Each further failure of the command doubles it
 * @param transientBackoffMaxMs the longest pause before a retry of a transient failure, in milliseconds; at least
 *     the base
 * @param maxStepsPerSegment the nodes one segment may execute before it is failed; at least 1
 * @param stuckAfterSeconds how long a command may stay unsettled after it first became available before it is
 *     reported stuck, in seconds; at least 1
 * @param enabled whether this node runs the workflow executor. {@code false} makes the node recording-only: it
 *     records starts, signals, completions, cancels and reassignments and serves queries, but deploys no executor,
 *     builds no plan and loads no workflow class, and it refuses the operations that need a build
 */
public record WorkflowExecutorConfig(
        int instances,
        int concurrency,
        long pollIntervalMs,
        int leaseSeconds,
        int maxAttempts,
        long transientBackoffBaseMs,
        long transientBackoffMaxMs,
        int maxStepsPerSegment,
        long stuckAfterSeconds,
        boolean enabled) {

    /** The default number of executor instances per node. */
    public static final int DEFAULT_INSTANCES = 1;

    /** The default number of segments one instance keeps in flight. */
    public static final int DEFAULT_CONCURRENCY = 8;

    /** The default pause between two looks for runnable commands, in milliseconds. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000L;

    /** The default claim lease, in seconds. */
    public static final int DEFAULT_LEASE_SECONDS = 60;

    /** The default number of claims a command may spend before it is dead-lettered. */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** The default pause before the first retry of a transient failure, in milliseconds. */
    public static final long DEFAULT_TRANSIENT_BACKOFF_BASE_MS = 200L;

    /** The default longest pause before a retry of a transient failure, in milliseconds. */
    public static final long DEFAULT_TRANSIENT_BACKOFF_MAX_MS = 30_000L;

    /** The default number of nodes one segment may execute. */
    public static final int DEFAULT_MAX_STEPS_PER_SEGMENT = 1000;

    /** The default age after which an unsettled command is reported stuck, in seconds. */
    public static final long DEFAULT_STUCK_AFTER_SECONDS = 300L;

    /** Whether a node runs the executor unless the configuration turns it off. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Creates an executor-role configuration with every component given except the node role.
     *
     * @param instances      the verticle instances of the executor on this node; 1 to 64
     * @param concurrency    the segments one instance keeps in flight at a time; 1 to 256
     * @param pollIntervalMs the pause between two looks for runnable commands, in milliseconds; at least 100
     * @param leaseSeconds   how long a claim holds a command, in seconds; at least 5
     * @param maxAttempts    the claims that may end by a crash or a lease overrun before dead-lettering; at least 1
     * @param transientBackoffBaseMs the pause before the first retry of a transient failure, in milliseconds
     * @param transientBackoffMaxMs the longest pause before a retry of a transient failure, in milliseconds
     * @param maxStepsPerSegment the nodes one segment may execute before it is failed; at least 1
     * @param stuckAfterSeconds how long a command may stay unsettled before it is reported stuck; at least 1
     * @throws ConfigurationException naming the offending key if a value is outside its bound
     */
    public WorkflowExecutorConfig(
            int instances,
            int concurrency,
            long pollIntervalMs,
            int leaseSeconds,
            int maxAttempts,
            long transientBackoffBaseMs,
            long transientBackoffMaxMs,
            int maxStepsPerSegment,
            long stuckAfterSeconds) {
        this(
                instances,
                concurrency,
                pollIntervalMs,
                leaseSeconds,
                maxAttempts,
                transientBackoffBaseMs,
                transientBackoffMaxMs,
                maxStepsPerSegment,
                stuckAfterSeconds,
                DEFAULT_ENABLED);
    }

    /**
     * Creates a configuration with the defaults for attempts, backoff, step cap and stuck age.
     *
     * @param instances      the verticle instances of the executor on this node; 1 to 64
     * @param concurrency    the segments one instance keeps in flight at a time; 1 to 256
     * @param pollIntervalMs the pause between two looks for runnable commands, in milliseconds; at least 100
     * @param leaseSeconds   how long a claim holds a command, in seconds; at least 5
     * @throws ConfigurationException naming the offending key if a value is outside its bound
     */
    public WorkflowExecutorConfig(int instances, int concurrency, long pollIntervalMs, int leaseSeconds) {
        this(
                instances,
                concurrency,
                pollIntervalMs,
                leaseSeconds,
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_TRANSIENT_BACKOFF_BASE_MS,
                DEFAULT_TRANSIENT_BACKOFF_MAX_MS,
                DEFAULT_MAX_STEPS_PER_SEGMENT,
                DEFAULT_STUCK_AFTER_SECONDS,
                DEFAULT_ENABLED);
    }

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
        if (maxAttempts < 1) {
            throw new ConfigurationException("workflow.executor.maxAttempts must be at least 1 but was " + maxAttempts);
        }
        if (transientBackoffBaseMs < 1) {
            throw new ConfigurationException(
                    "workflow.executor.transientBackoffBaseMs must be at least 1 but was " + transientBackoffBaseMs);
        }
        if (transientBackoffMaxMs < transientBackoffBaseMs) {
            throw new ConfigurationException("workflow.executor.transientBackoffMaxMs must be at least "
                    + "transientBackoffBaseMs (" + transientBackoffBaseMs + ") but was " + transientBackoffMaxMs);
        }
        if (maxStepsPerSegment < 1) {
            throw new ConfigurationException(
                    "workflow.executor.maxStepsPerSegment must be at least 1 but was " + maxStepsPerSegment);
        }
        if (stuckAfterSeconds < 1) {
            throw new ConfigurationException(
                    "workflow.executor.stuckAfterSeconds must be at least 1 but was " + stuckAfterSeconds);
        }
    }

    @JsonCreator
    static WorkflowExecutorConfig fromJson(
            @JsonProperty("instances") @Nullable Integer instances,
            @JsonProperty("concurrency") @Nullable Integer concurrency,
            @JsonProperty("pollIntervalMs") @Nullable Long pollIntervalMs,
            @JsonProperty("leaseSeconds") @Nullable Integer leaseSeconds,
            @JsonProperty("maxAttempts") @Nullable Integer maxAttempts,
            @JsonProperty("transientBackoffBaseMs") @Nullable Long transientBackoffBaseMs,
            @JsonProperty("transientBackoffMaxMs") @Nullable Long transientBackoffMaxMs,
            @JsonProperty("maxStepsPerSegment") @Nullable Integer maxStepsPerSegment,
            @JsonProperty("stuckAfterSeconds") @Nullable Long stuckAfterSeconds,
            @JsonProperty("enabled") @Nullable Boolean enabled) {
        return new WorkflowExecutorConfig(
                instances == null ? DEFAULT_INSTANCES : instances,
                concurrency == null ? DEFAULT_CONCURRENCY : concurrency,
                pollIntervalMs == null ? DEFAULT_POLL_INTERVAL_MS : pollIntervalMs,
                leaseSeconds == null ? DEFAULT_LEASE_SECONDS : leaseSeconds,
                maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts,
                transientBackoffBaseMs == null ? DEFAULT_TRANSIENT_BACKOFF_BASE_MS : transientBackoffBaseMs,
                transientBackoffMaxMs == null ? DEFAULT_TRANSIENT_BACKOFF_MAX_MS : transientBackoffMaxMs,
                maxStepsPerSegment == null ? DEFAULT_MAX_STEPS_PER_SEGMENT : maxStepsPerSegment,
                stuckAfterSeconds == null ? DEFAULT_STUCK_AFTER_SECONDS : stuckAfterSeconds,
                enabled == null ? DEFAULT_ENABLED : enabled);
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
