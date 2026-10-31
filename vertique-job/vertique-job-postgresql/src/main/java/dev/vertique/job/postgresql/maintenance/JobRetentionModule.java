// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.services.Services;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module that adds the cluster-singleton job execution retention job.
 *
 * <p>Include it next to {@link JobPostgresqlModule} and the persistence-backed cron module
 * ({@code CronPersistenceModule}) to delete finished executions, and their logs, once they pass the
 * retention age in {@link JobRetentionConfig}. It is opt-in: an application that uses delayed jobs
 * without cron is not required to install cron, and keeps every execution until it deletes them
 * itself. An application that includes this module without the persistence-backed cron fails at
 * compose time (see {@link JobRetentionComposeValidator}) instead of starting with the job
 * silently unscheduled.
 *
 * <pre>{@code
 * @Component(modules = {DbPostgresqlModule.class, JobPostgresqlModule.class,
 *                       CronPersistenceModule.class, JobRetentionModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 */
@Module
public abstract class JobRetentionModule {

    /**
     * Provides {@link JobRetentionConfig} deserialized from the {@code job.retention} section of the
     * application config. Missing keys fall back to the defaults.
     *
     * @param config the root application config JSON object
     * @param parser the injected config parser
     * @return the retention configuration
     */
    @Provides
    @Singleton
    static JobRetentionConfig jobRetentionConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "job", "retention"), JobRetentionConfig.class)
                .validate();
    }

    /**
     * Contributes {@link JobMaintenanceCron} into the {@code @Services} multibinding so the cron
     * registrar discovers its {@code @CronJob}-annotated method.
     *
     * @param impl the maintenance cron entry point
     * @return the impl as a raw {@link Object} (required by the {@code @Services} binding shape)
     */
    @Provides
    @Singleton
    @IntoSet
    @Services
    static Object jobMaintenance(JobMaintenanceCron impl) {
        return impl;
    }

    /**
     * Contributes {@link JobRetentionComposeValidator} so the framework builds it in the validate
     * phase, which checks the cron bindings it depends on.
     *
     * @param impl the validator
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator jobRetentionComposeValidator(JobRetentionComposeValidator impl) {
        return impl;
    }
}
