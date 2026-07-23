// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the {@link JobCoordinator}.
 *
 * <p>Deserialized from the {@code job.coordinator} section of the application config. All
 * intervals are in milliseconds and accept {@code @Builder.Default} values so that the
 * coordinator can be constructed without any external config in unit tests.
 *
 * <p>Example YAML:
 * <pre>{@code
 * job:
 *   coordinator:
 *     enabled: true
 *     nodeHeartbeatIntervalMs: 10000
 *     nodeHeartbeatTimeoutMs: 60000
 *     scanIntervalMs: 30000
 *     executionTimeoutMs: 120000
 *     progressFlushIntervalMs: 10000
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class JobCoordinatorConfig {

    /**
     * Whether the coordinator is enabled. When {@code false}, no timers are started and the
     * coordinator performs no background activity.
     */
    @Builder.Default
    private final boolean enabled = true;

    /**
     * Interval in milliseconds at which this node writes a heartbeat to
     * {@code job_server_heartbeats}. Default: 10 000 ms (10 s).
     */
    @Builder.Default
    private final long nodeHeartbeatIntervalMs = 10_000;

    /**
     * Duration in milliseconds after which a server's heartbeat is considered expired and the
     * server is treated as dead. Default: 60 000 ms (60 s, i.e. 6× the heartbeat interval).
     */
    @Builder.Default
    private final long nodeHeartbeatTimeoutMs = 60_000;

    /**
     * Interval in milliseconds between dead-node scans. Default: 30 000 ms (30 s).
     */
    @Builder.Default
    private final long scanIntervalMs = 30_000;

    /**
     * Default execution timeout in milliseconds. When a consumer-side timeout fires, the
     * execution is marked {@link JobState#ABANDONED}. Set to 0 to disable. Default: 120 000 ms
     * (2 min).
     */
    @Builder.Default
    private final long executionTimeoutMs = 120_000;

    /**
     * Interval in milliseconds for flushing execution progress to the database. Only writes when
     * the snapshot has changed since the last flush. Default: 10 000 ms (10 s).
     */
    @Builder.Default
    private final long progressFlushIntervalMs = 10_000;
}
