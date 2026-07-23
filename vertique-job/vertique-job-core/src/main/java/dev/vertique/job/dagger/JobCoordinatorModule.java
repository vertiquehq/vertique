// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.dagger;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.job.JobCoordinator;
import dev.vertique.job.JobCoordinatorConfig;
import dev.vertique.job.JobRepository;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module that provides the {@link JobCoordinator} and {@link JobCoordinatorConfig}.
 *
 * <p>Reads coordinator settings from the {@code job.coordinator} section of the application
 * config. When no config is present, all fields fall back to their {@code @Builder.Default}
 * values (enabled, 10 s heartbeat, 60 s timeout, 30 s scan interval, 2 min execution timeout,
 * 10 s progress-flush interval).
 *
 * <p>Include this module in application Dagger components that use either
 * {@link dev.vertique.job.cron.dagger.CronPersistenceModule} or
 * {@link dev.vertique.job.delayed.dagger.DelayedJobModule}, both of which transitively include
 * this module and consume {@link JobCoordinatorConfig} to configure per-execution timeouts and
 * progress-flush intervals.
 *
 * <p>Example YAML configuration:
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
@Module
public abstract class JobCoordinatorModule {

    /**
     * Provides the {@link JobCoordinatorConfig} deserialized from the {@code job.coordinator}
     * section of the application config. Missing keys fall back to annotated defaults.
     *
     * @param config the root application config JSON object
     * @param parser the injected config parser
     * @return the coordinator configuration
     */
    @Provides
    @Singleton
    static JobCoordinatorConfig jobCoordinatorConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "job", "coordinator"), JobCoordinatorConfig.class);
    }

    /**
     * Provides the singleton {@link JobCoordinator} wired with the bound repository and config.
     *
     * @param vertx      the Vert.x instance for timer scheduling and event-bus access
     * @param repository the job repository for heartbeat writes and execution recovery
     * @param config     the coordinator configuration
     * @return the singleton job coordinator
     */
    @Provides
    @Singleton
    static JobCoordinator jobCoordinator(Vertx vertx, JobRepository repository, JobCoordinatorConfig config) {
        return new JobCoordinator(vertx, repository, config);
    }
}
