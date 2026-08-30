// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module providing Flyway configuration and migration runner bindings.
 *
 * <p>Include alongside {@link dev.vertique.db.DbModule} in your Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {DbModule.class, DbFlywayModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 */
@Module
public abstract class DbFlywayModule {

    /**
     * Provides the Flyway configuration from the {@code "flyway"} config section.
     *
     * @param config the application config
     * @param parser the injected config parser
     * @return the Flyway configuration
     */
    @Provides
    @Singleton
    static FlywayConfig flywayConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "flyway"), FlywayConfig.class);
    }

    /**
     * Binds the {@link FlywayMigrationRunner} as the {@link MigrationRunner} implementation.
     *
     * @param runner the Flyway migration runner
     * @return the migration runner
     */
    @Provides
    @Singleton
    static MigrationRunner migrationRunner(FlywayMigrationRunner runner) {
        return runner;
    }

    /**
     * Contributes the {@link FlywayMigrationStartupStep} into the {@code Set<ApplicationStartupStep>}
     * multibinding.
     *
     * <p>The step runs schema migrations via {@link MigrationRunner#migrate(io.vertx.core.Vertx)} in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#MIGRATE MIGRATE} phase, before any verticle is
     * deployed. It is the standalone-lifecycle replacement for the manual {@code migrationRunner().migrate(vertx)}
     * call applications previously made by hand. Dagger constructs the step via its {@code @Inject}
     * constructor.
     *
     * @param step the Flyway migration startup step
     * @return the step as an {@link ApplicationStartupStep}
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationStartupStep flywayMigrationStartupStep(FlywayMigrationStartupStep step) {
        return step;
    }
}
