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
import java.util.ArrayList;
import java.util.List;
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

    /** Fields a {@code flyway.instances.<name>} object may contain. */
    private static final Set<String> INSTANCE_FIELDS = Set.of(
            "mode",
            "locations",
            "schemas",
            "table",
            "target",
            "outOfOrder",
            "baselineOnMigrate",
            "baselineVersion",
            "validateOnMigrate",
            "placeholders",
            "user",
            "password");

    private static final String INSTANCES = "instances";

    /**
     * Provides the Flyway configuration from the {@code "flyway"} config section.
     *
     * <p>The raw {@code instances} value decides the mode: absent or JSON {@code null} keeps the
     * single-Flyway configuration, and an object, even an empty one, selects named mode. The root
     * fields are parsed exactly as before in single-Flyway mode; in named mode an unreadable root field
     * fails like an instance field, without a cause or an echoed value. Each named instance object may contain only the documented
     * override fields; any other key, a non-object value or an unreadable field fails with a
     * {@link MigrationRunException} (operation {@code configuration}) that names the instance and
     * retains no cause, because an instance object can carry a password and placeholder values.
     *
     * @param config the application config
     * @param parser the injected config parser
     * @return the Flyway configuration
     * @throws MigrationRunException if the named instances or the root fields of a named configuration
     *     are malformed
     */
    @Provides
    @Singleton
    static FlywayConfig flywayConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(config, "flyway");
        if (!section.containsKey(INSTANCES)) {
            return parser.parse(section, FlywayConfig.class);
        }
        Object raw = section.getValue(INSTANCES);
        JsonObject root = section.copy();
        root.remove(INSTANCES);
        if (raw == null) {
            return parser.parse(root, FlywayConfig.class);
        }
        if (!(raw instanceof JsonObject instancesObject)) {
            throw bindingFailure(null, "flyway.instances must be a JSON object keyed by instance name");
        }
        for (String name : instancesObject.fieldNames()) {
            if (!(instancesObject.getValue(name) instanceof JsonObject entry)) {
                throw bindingFailure(name, "the instance value must be a JSON object");
            }
            for (String field : entry.fieldNames()) {
                if (!INSTANCE_FIELDS.contains(field)) {
                    throw bindingFailure(
                            name,
                            "the field '" + field + "' is not a supported instance override; supported fields are "
                                    + INSTANCE_FIELDS.stream().sorted().toList());
                }
            }
        }
        FlywayConfig parsed;
        try {
            parsed = parser.parse(root, FlywayConfig.class);
        } catch (RuntimeException ex) {
            throw bindingFailure(null, "the flyway section could not be read; check the type of every field");
        }
        List<FlywayInstanceConfig> instances = new ArrayList<>();
        for (String name : instancesObject.fieldNames()) {
            JsonObject single = new JsonObject().put(name, instancesObject.getJsonObject(name));
            try {
                instances.addAll(parser.parseKeyedObject(single, "name", FlywayInstanceConfig.class));
            } catch (RuntimeException ex) {
                throw bindingFailure(name, "the instance overrides could not be read; check the type of every field");
            }
        }
        return parsed.withInstances(instances);
    }

    private static MigrationRunException bindingFailure(String instance, String detail) {
        return NamedMigrationFailure.of(NamedMigrationFailure.CONFIGURATION, instance, detail)
                .toException(NamedMigrationFailure.OP_CONFIGURATION, null);
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
