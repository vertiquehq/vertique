// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.job;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.examples.sse.resource.JobResource;
import dev.vertique.rest.core.dagger.JaxRsResources;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module that provides the {@link JobService} singleton and its configuration bindings.
 *
 * <p>Also contributes {@link JobResource} to the JAX-RS resource multibinding so the framework
 * registers its routes automatically.
 */
@Module
public class JobModule {

    /**
     * Provides the job pipeline configuration from the {@code "job"} config section.
     *
     * @param cfg    the application configuration
     * @param parser the injected config parser
     * @return the typed job configuration; defaults to 50 ms interval and 5 steps when the section
     *     is absent
     */
    @Provides
    @Singleton
    static JobConfig jobConfig(@VertxConfig JsonObject cfg, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(cfg, "job"), JobConfig.class);
    }

    /**
     * Contributes {@link JobResource} to the JAX-RS resource multibinding.
     *
     * @param resource the resource instance provided by Dagger
     * @return the resource as an {@link Object} for the multibinding set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object jobResource(JobResource resource) {
        return resource;
    }
}
