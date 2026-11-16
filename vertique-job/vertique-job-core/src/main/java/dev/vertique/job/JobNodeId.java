// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;

/**
 * Qualifies the {@code String} node identity this JVM uses for job scheduling.
 *
 * <p>{@link dev.vertique.job.dagger.JobModule} binds one {@code @JobNodeId String} per component:
 * the host name followed by a hyphen and an 8-character random suffix ({@code unknown} as the host
 * name when it cannot be resolved). The {@link JobCoordinator} writes its node heartbeat under this
 * value, and the PostgreSQL job repository and the persistence-backed cron scheduler stamp it as
 * {@code locked_by} on the executions this node runs. Because the heartbeat and the claims carry the
 * same identity, the coordinator of a surviving node finds a dead node's executions by its heartbeat
 * row and recovers them.
 *
 * <p>A custom {@link JobRepository} that records claim ownership should inject this value rather
 * than generate its own, or dead-node recovery cannot match its executions.
 */
@Qualifier
@Retention(RUNTIME)
public @interface JobNodeId {}
