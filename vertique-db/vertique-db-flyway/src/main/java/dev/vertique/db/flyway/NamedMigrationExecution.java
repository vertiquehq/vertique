// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.flyway.MigrationRunException.Stage;
import dev.vertique.db.flyway.NamedInstanceResolver.Instance;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes one validated named batch on the calling (worker) thread: every enabled instance runs
 * through native Flyway in order, the run stops at the first failure and every outcome is reported.
 *
 * <p>Cross-process safety is Flyway's own: each instance migrates under the lock Flyway takes for its
 * history table, so two processes that start together serialise per instance and the second one finds
 * nothing left to apply.
 *
 * <p>A failure becomes a {@link MigrationRunException} whose message is a fixed sentence plus the Flyway
 * error-code name and the SQLState when present, and whose cause is the native exception.
 */
final class NamedMigrationExecution {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private final List<Instance> instances;
    private final FlywayConfig config;
    private final ClassLoader loader;
    private final Supplier<String> jdbcUrl;

    /**
     * Creates an execution.
     *
     * @param instances the resolved instances in execution order
     * @param config the root Flyway configuration, for settings without a per-instance override
     * @param loader the caller's class loader, passed to native Flyway
     * @param jdbcUrl resolves the JDBC destination; throws {@link IllegalStateException} when none is
     *     configured
     */
    NamedMigrationExecution(
            List<Instance> instances, FlywayConfig config, ClassLoader loader, Supplier<String> jdbcUrl) {
        this.instances = instances;
        this.config = config;
        this.loader = loader;
        this.jdbcUrl = jdbcUrl;
    }

    /** Outcome of a successful batch: the report and, for a single entry, the native target version. */
    record Outcome(MigrationReport report, String targetVersion) {}

    /**
     * Runs the batch.
     *
     * @return the outcome
     * @throws MigrationRunException when the batch fails; its report lists every outcome
     */
    Outcome execute() {
        List<MigrationInstanceResult> results = new ArrayList<>();
        for (Instance instance : instances) {
            boolean disabled = instance.view().mode() == FlywayMode.DISABLED;
            results.add(new MigrationInstanceResult(
                    instance.view(), disabled ? MigrationStatus.DISABLED : MigrationStatus.NOT_RUN, 0, null));
        }
        if (results.stream().allMatch(result -> result.status() == MigrationStatus.DISABLED)) {
            log.info("Named Flyway migration disabled for every instance");
            return new Outcome(new MigrationReport(results), null);
        }
        String url;
        try {
            url = jdbcUrl.get();
        } catch (IllegalStateException ex) {
            throw new MigrationRunException(
                    "Named migration run failed: either flyway.jdbcUrl or db.host and db.database must be configured",
                    null,
                    Stage.CONFIGURATION,
                    new MigrationReport(results));
        }
        String targetVersion = null;
        for (int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            MigrationInstanceView view = instance.view();
            if (view.mode() == FlywayMode.DISABLED) {
                continue;
            }
            boolean migrate = view.mode() == FlywayMode.MIGRATE;
            Flyway flyway;
            int applied = 0;
            try {
                flyway = configure(instance, url).load();
                if (migrate) {
                    log.info("Running Flyway migration for instance {}", view.name());
                    MigrateResult result = flyway.migrate();
                    applied = result.migrationsExecuted;
                    targetVersion = result.targetSchemaVersion;
                } else {
                    log.info("Validating Flyway schema for instance {}", view.name());
                    flyway.validate();
                }
            } catch (RuntimeException ex) {
                results.set(i, new MigrationInstanceResult(view, MigrationStatus.FAILED, 0, null));
                log.warn("Flyway {} failed for instance {}", view.mode(), view.name());
                throw new MigrationRunException(
                        "Named migration run failed for instance '" + view.name() + "': "
                                + failureDetail(view.mode(), ex),
                        ex,
                        migrate ? Stage.MIGRATION : Stage.VALIDATION,
                        new MigrationReport(results));
            }
            results.set(
                    i,
                    new MigrationInstanceResult(
                            view,
                            migrate ? MigrationStatus.MIGRATED : MigrationStatus.VALIDATED,
                            applied,
                            readCurrentVersion(flyway, view.name())));
            log.info("Flyway {} complete for instance {}: {} migrations applied", view.mode(), view.name(), applied);
        }
        return new Outcome(new MigrationReport(results), results.size() == 1 ? targetVersion : null);
    }

    private FluentConfiguration configure(Instance instance, String url) {
        MigrationInstanceView view = instance.view();
        FluentConfiguration cfg = Flyway.configure(loader)
                .dataSource(url, view.user(), instance.password())
                .locations(view.locations().toArray(String[]::new))
                .table(view.table())
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .outOfOrder(view.outOfOrder())
                .cleanDisabled(config.cleanDisabled())
                .baselineOnMigrate(view.baselineOnMigrate())
                .baselineVersion(view.baselineVersion())
                .validateOnMigrate(view.validateOnMigrate());
        if (!view.schemas().isEmpty()) {
            cfg.schemas(view.schemas().toArray(String[]::new)).defaultSchema(view.schema());
        }
        if (view.target() != null) {
            cfg.target(view.target());
        }
        if (!instance.placeholders().isEmpty()) {
            cfg.placeholders(instance.placeholders());
        }
        if (instance.lockRetryCount() != null) {
            cfg.lockRetryCount(instance.lockRetryCount());
        }
        return cfg;
    }

    // --- Native metadata ---

    /**
     * Reads the current version through Flyway info.
     *
     * @param flyway the configured Flyway
     * @return the current version, or {@code null} when no versioned migration is applied
     */
    static String currentVersion(Flyway flyway) {
        MigrationInfo current = flyway.info().current();
        return current == null || current.getVersion() == null
                ? null
                : current.getVersion().getVersion();
    }

    /** The operation already succeeded, so a failed version read leaves the version unknown. */
    private static String readCurrentVersion(Flyway flyway, String instance) {
        try {
            return currentVersion(flyway);
        } catch (RuntimeException ex) {
            log.warn("The current version of instance {} could not be read after the operation", instance);
            return null;
        }
    }

    private static String failureDetail(FlywayMode mode, RuntimeException ex) {
        Enum<?> errorCode = null;
        String sqlState = "";
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof FlywayException flywayException
                    && flywayException.getErrorCode() instanceof Enum<?> code) {
                if (code == CoreErrorCode.NON_EMPTY_SCHEMA_WITHOUT_SCHEMA_HISTORY_TABLE) {
                    return "the instance's default schema already contains objects but has no migration history for"
                            + " this instance. Either give the instance its own schema with a separate db.schemas"
                            + " entry, or set baselineOnMigrate=true with baselineVersion=\"0\" for this instance so"
                            + " that every migration still runs";
                }
                if (errorCode == null && code != CoreErrorCode.ERROR) {
                    errorCode = code;
                }
            }
            if (sqlState.isEmpty()
                    && t instanceof SQLException sql
                    && sql.getSQLState() != null
                    && SQL_STATE.matcher(sql.getSQLState()).matches()) {
                sqlState = " (SQLState " + sql.getSQLState() + ")";
            }
        }
        return "the native " + mode + " operation failed"
                + (errorCode == null ? "" : " (Flyway error " + errorCode.name() + ")") + sqlState;
    }
}
