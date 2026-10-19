// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static dev.vertique.db.flyway.NamedMigrationFailure.COORDINATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.MIGRATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_COORDINATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_RUN;
import static dev.vertique.db.flyway.NamedMigrationFailure.PREFLIGHT;
import static dev.vertique.db.flyway.NamedMigrationFailure.VALIDATION;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes one validated named batch on the calling (worker) thread: opens the database-support
 * session, runs every enabled instance through native Flyway in order, stops at the first failure
 * and reports every outcome.
 *
 * <p>Failures become {@link MigrationRunException}s with framework-authored messages. A native
 * throwable is never retained, logged or copied: only its Flyway error-code name and a SQLState are
 * used.
 */
final class NamedMigrationExecution {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
    private static final int MAX_CAUSE_DEPTH = 32;

    private final NamedPlan plan;
    private final FlywayConfig config;
    private final ClassLoader loader;

    /**
     * Creates an execution.
     *
     * @param plan the validated batch
     * @param config the root Flyway configuration, for settings without a per-instance override
     * @param loader the caller's class loader, passed to native Flyway
     */
    NamedMigrationExecution(NamedPlan plan, FlywayConfig config, ClassLoader loader) {
        this.plan = plan;
        this.config = config;
        this.loader = loader;
    }

    /** Outcome of a successful batch: the report and, for a single entry, the native target version. */
    record Outcome(MigrationReport report, String targetVersion) {}

    /**
     * Returns a report that lists every view as not run.
     *
     * @param views the views in execution order
     * @return the report
     */
    static MigrationReport notRun(List<MigrationInstanceView> views) {
        return new MigrationReport(views.stream()
                .map(view -> new MigrationInstanceResult(view, MigrationStatus.NOT_RUN, 0, null))
                .toList());
    }

    /**
     * Runs the batch.
     *
     * @return the outcome
     * @throws MigrationRunException when the batch fails; its report lists every outcome
     */
    Outcome execute() {
        List<MigrationInstanceView> views = plan.views();
        List<MigrationInstanceResult> results = new ArrayList<>(notRun(views).instances());
        boolean anyEnabled = views.stream().anyMatch(view -> view.mode() != FlywayMode.DISABLED);
        if (!anyEnabled) {
            log.info("Named Flyway migration disabled for every instance");
            return new Outcome(
                    new MigrationReport(views.stream()
                            .map(view -> new MigrationInstanceResult(view, MigrationStatus.DISABLED, 0, null))
                            .toList()),
                    null);
        }

        DatabaseSupport.Session session = openSession(results);
        String targetVersion = null;
        try {
            preflight(session, views, results);
            for (int i = 0; i < plan.instances().size(); i++) {
                NamedPlan.Instance instance = plan.instances().get(i);
                MigrationInstanceView view = instance.view();
                if (view.mode() == FlywayMode.DISABLED) {
                    results.set(i, new MigrationInstanceResult(view, MigrationStatus.DISABLED, 0, null));
                    continue;
                }
                beforeInstance(session, i, view, results);
                targetVersion = runInstance(i, instance, results);
            }
        } finally {
            closeQuietly(session);
        }
        return new Outcome(new MigrationReport(results), results.size() == 1 ? targetVersion : null);
    }

    // --- Database-support session ---

    private DatabaseSupport.Session openSession(List<MigrationInstanceResult> results) {
        try {
            return plan.support().open(plan.jdbcUrl(), plan.sessionUser(), plan.sessionPassword());
        } catch (SQLException ex) {
            throw NamedMigrationFailure.of(
                            COORDINATION, null, "the database session could not be opened" + sqlState(ex))
                    .toException(OP_COORDINATION, new MigrationReport(results));
        } catch (MigrationRunException ex) {
            throw rewrap(ex, results);
        } catch (RuntimeException ex) {
            throw NamedMigrationFailure.of(COORDINATION, null, "the database session could not be opened")
                    .toException(OP_COORDINATION, new MigrationReport(results));
        }
    }

    private void preflight(
            DatabaseSupport.Session session, List<MigrationInstanceView> views, List<MigrationInstanceResult> results) {
        List<MigrationInstanceView> enabled = views.stream()
                .filter(view -> view.mode() != FlywayMode.DISABLED)
                .toList();
        try {
            session.preflight(enabled);
        } catch (MigrationRunException ex) {
            throw rewrap(ex, results);
        } catch (RuntimeException ex) {
            throw NamedMigrationFailure.of(PREFLIGHT, null, "the batch preflight check failed")
                    .toException(OP_RUN, new MigrationReport(results));
        }
    }

    private static void beforeInstance(
            DatabaseSupport.Session session,
            int index,
            MigrationInstanceView view,
            List<MigrationInstanceResult> results) {
        try {
            session.beforeInstance(view);
        } catch (MigrationRunException ex) {
            results.set(index, new MigrationInstanceResult(view, MigrationStatus.FAILED, 0, null));
            throw rewrap(ex, results);
        } catch (RuntimeException ex) {
            results.set(index, new MigrationInstanceResult(view, MigrationStatus.FAILED, 0, null));
            throw NamedMigrationFailure.of(PREFLIGHT, view.name(), "the pre-migration check failed")
                    .toException(OP_RUN, new MigrationReport(results));
        }
    }

    /** Re-issues a database-support rejection with the run's operation and current report. */
    private static MigrationRunException rewrap(MigrationRunException ex, List<MigrationInstanceResult> results) {
        String operation = OP_COORDINATION.equals(ex.operation()) ? OP_COORDINATION : OP_RUN;
        return NamedMigrationFailure.reissue(ex, operation, new MigrationReport(results));
    }

    private static void closeQuietly(DatabaseSupport.Session session) {
        try {
            session.close();
        } catch (RuntimeException ex) {
            log.warn("The named migration database session did not close cleanly");
        }
    }

    // --- One instance ---

    /** Runs one enabled instance and records its result; returns the native target version of a migrate. */
    private String runInstance(int index, NamedPlan.Instance instance, List<MigrationInstanceResult> results) {
        MigrationInstanceView view = instance.view();
        boolean migrate = view.mode() == FlywayMode.MIGRATE;
        try {
            Flyway flyway = configure(instance).load();
            int applied = 0;
            String targetVersion = null;
            if (migrate) {
                log.info("Running Flyway migration for instance {}", view.name());
                MigrateResult result = flyway.migrate();
                applied = result.migrationsExecuted;
                targetVersion = result.targetSchemaVersion;
            } else {
                log.info("Validating Flyway schema for instance {}", view.name());
                flyway.validate();
            }
            String schema = view.schema() != null ? view.schema() : connectionSchema(flyway);
            results.set(
                    index,
                    new MigrationInstanceResult(
                            view.withSchema(schema),
                            migrate ? MigrationStatus.MIGRATED : MigrationStatus.VALIDATED,
                            applied,
                            currentVersion(flyway)));
            log.info("Flyway {} complete for instance {}: {} migrations applied", view.mode(), view.name(), applied);
            return targetVersion;
        } catch (RuntimeException ex) {
            results.set(index, new MigrationInstanceResult(view, MigrationStatus.FAILED, 0, null));
            String failureCode = migrate ? MIGRATION : VALIDATION;
            log.warn("Flyway {} failed for instance {} ({})", view.mode(), view.name(), failureCode);
            throw NamedMigrationFailure.of(failureCode, view.name(), failureDetail(view.mode(), ex))
                    .toException(OP_RUN, new MigrationReport(results));
        }
    }

    private FluentConfiguration configure(NamedPlan.Instance instance) {
        MigrationInstanceView view = instance.view();
        FluentConfiguration cfg = Flyway.configure(loader)
                .dataSource(plan.jdbcUrl(), view.user(), instance.password())
                .locations(view.locations().toArray(String[]::new))
                .table(view.table())
                .outOfOrder(view.outOfOrder())
                .cleanDisabled(config.cleanDisabled())
                .baselineOnMigrate(view.baselineOnMigrate())
                .baselineVersion(view.baselineVersion())
                .validateOnMigrate(view.validateOnMigrate());
        if (!view.schemas().isEmpty()) {
            cfg.schemas(view.schemas().toArray(String[]::new));
        }
        if (view.schema() != null) {
            cfg.defaultSchema(view.schema());
        }
        if (view.target() != null) {
            cfg.target(view.target());
        }
        if (!instance.placeholders().isEmpty()) {
            cfg.placeholders(instance.placeholders());
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

    /**
     * Reads the default schema of a connection opened through Flyway's own data source. Used only when
     * no schema is configured, to report where the history really lives.
     *
     * @param flyway the configured Flyway
     * @return the connection's current schema, or {@code null} when the driver reports none
     * @throws FlywayException if the connection cannot be opened or queried
     */
    static String connectionSchema(Flyway flyway) {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            return connection.getSchema();
        } catch (SQLException ex) {
            throw new FlywayException("Unable to read the connection's current schema", ex);
        }
    }

    private static String failureDetail(FlywayMode mode, RuntimeException ex) {
        ErrorCode errorCode = errorCode(ex);
        if (errorCode == CoreErrorCode.NON_EMPTY_SCHEMA_WITHOUT_SCHEMA_HISTORY_TABLE) {
            return "the instance's default schema already contains objects but has no migration history for this"
                    + " instance. Either give the instance its own schema with a separate db.schemas entry, or set"
                    + " baselineOnMigrate=true with baselineVersion=\"0\" for this instance so that every"
                    + " migration still runs";
        }
        String code = errorCode instanceof Enum<?> constant ? " (Flyway error " + constant.name() + ")" : "";
        return "the native " + mode + " operation failed" + code + sqlState(ex);
    }

    /** Finds the most specific Flyway error code in the cause chain, ignoring the generic {@code ERROR}. */
    private static ErrorCode errorCode(Throwable failure) {
        ErrorCode found = null;
        for (Throwable current : causeChain(failure)) {
            if (current instanceof FlywayException flywayException && flywayException.getErrorCode() != null) {
                ErrorCode code = flywayException.getErrorCode();
                if (code == CoreErrorCode.NON_EMPTY_SCHEMA_WITHOUT_SCHEMA_HISTORY_TABLE) {
                    return code;
                }
                if (found == null || found == CoreErrorCode.ERROR) {
                    found = code;
                }
            }
        }
        return found;
    }

    /** Returns {@code " (SQLState XXXXX)"} for the first well-formed SQLState in the chain, else empty. */
    private static String sqlState(Throwable failure) {
        for (Throwable current : causeChain(failure)) {
            if (current instanceof SQLException sql
                    && sql.getSQLState() != null
                    && SQL_STATE.matcher(sql.getSQLState()).matches()) {
                return " (SQLState " + sql.getSQLState() + ")";
            }
        }
        return "";
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && chain.size() < MAX_CAUSE_DEPTH && visited.add(current);
                current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
