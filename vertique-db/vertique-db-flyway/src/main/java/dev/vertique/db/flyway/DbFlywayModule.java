// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.DbSchemasModule;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module providing Flyway configuration and migration runner bindings.
 *
 * <p>Include alongside {@link dev.vertique.db.DbModule} in your Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {DbModule.class, DbFlywayModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>Named Flyway instances are contributed explicitly: a module or the application adds
 * {@code @Provides @IntoSet MigrationDefinition} bindings, and a {@code flyway.instances} object opts
 * into named mode. This module declares the possibly empty {@code Set<MigrationDefinition>} and the
 * optional {@link DbSchemas} binding, so a component without declarations or without
 * {@link dev.vertique.db.DbModule} still compiles.
 */
@Module(includes = DbSchemasModule.class)
public abstract class DbFlywayModule {

    /**
     * Provides the Flyway configuration from the {@code "flyway"} config section.
     *
     * <p>The section is bound in one step. The {@code instances} value decides the mode: absent or JSON
     * {@code null} keeps the single-Flyway configuration, and an object keyed by instance name, even an
     * empty one, selects named mode; each key becomes the {@code name} of its entry. Root fields are read
     * the same way in both modes and unknown root keys are ignored. A named instance object may contain
     * only the documented override fields.
     *
     * @param config the application config
     * @param parser the injected config parser
     * @return the Flyway configuration
     * @throws dev.vertique.core.exception.ConfigurationException if the section cannot be bound: a field
     *     of the wrong type, {@code instances} that is not an object, an instance value that is not an
     *     object, or an unsupported key in an instance object. The message never contains a configuration
     *     value; a plain type error keeps the underlying binding failure as its cause
     */
    @Provides
    @Singleton
    static FlywayConfig flywayConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "flyway"), FlywayConfig.class);
    }

    /**
     * Declares the possibly empty set of wired migration declarations.
     *
     * @return the declaration set; never called
     */
    @Multibinds
    abstract Set<MigrationDefinition> migrationDefinitions();

    /**
     * Provides the configured runner. The placement comes from an optional {@link DbSchemas} binding
     * and is empty when the component has none.
     *
     * @param config the Flyway configuration
     * @param dbPoolConfig the pool configuration, for credential and JDBC URL fallback
     * @param definitions the wired migration declarations
     * @param schemas the optional placement binding
     * @return the runner
     */
    @Provides
    @Singleton
    static FlywayMigrationRunner flywayMigrationRunner(
            FlywayConfig config,
            DbPoolConfig dbPoolConfig,
            Set<MigrationDefinition> definitions,
            Optional<DbSchemas> schemas) {
        return new FlywayMigrationRunner(config, dbPoolConfig, definitions, schemas.orElse(DbSchemas.empty()));
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
