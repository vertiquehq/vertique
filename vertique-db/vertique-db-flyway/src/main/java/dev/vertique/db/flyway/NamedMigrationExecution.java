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
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes one validated named batch on the calling (worker) thread: opens the database-support
 * session, lets it check and freeze the batch, validates every existing history the session names
 * before any instance runs, runs every enabled instance through native Flyway in order, stops at the
 * first failure and reports every outcome.
 *
 * <p>Each enabled instance runs with the default schema the session froze for it bound as native
 * Flyway's default schema, and its report entry carries that frozen schema. Before an instance writes
 * anything, the effective native default schema, managed schema list and history table are read back
 * from the loaded Flyway and compared with the frozen view; a disagreement fails the instance before
 * any native operation.
 *
 * <p>Failures become {@link MigrationRunException}s with framework-authored messages. A native
 * throwable is never retained, logged or copied: only its Flyway error-code name and a SQLState are
 * used.
 */
final class NamedMigrationExecution {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    private static final int MAX_CAUSE_DEPTH = 32;

    /** The only ignore pattern of the validation an existing history must pass: pending work is allowed. */
    private static final String PENDING_ONLY = "*:pending";

    private static final String LATEST = "latest";

    private static final String DISAGREEING_CONFIGURATION =
            "the effective native configuration disagrees with the checked default schema, managed schemas or"
                    + " history table";
    private static final String INCONSISTENT_ANSWER = "the batch preflight check returned an inconsistent answer";

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

    /** The batch as the session froze it: every instance in execution order and the history owners. */
    private record Frozen(List<NamedPlan.Instance> instances, Set<String> historyOwners) {}

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
            Frozen frozen = preflight(session, results);
            validateHistoryOwners(frozen, results);
            for (int i = 0; i < frozen.instances().size(); i++) {
                NamedPlan.Instance instance = frozen.instances().get(i);
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
            return plan.support()
                    .open(plan.jdbcUrl(), plan.sessionUser(), plan.sessionPassword(), plan.lockTimeoutMs());
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

    /**
     * Lets the session check the enabled instances and returns the batch with the frozen views in place
     * of the enabled ones. The answer must keep every enabled instance in order and may only fill a
     * default schema that was unknown.
     */
    private Frozen preflight(DatabaseSupport.Session session, List<MigrationInstanceResult> results) {
        List<NamedPlan.Instance> enabled = plan.instances().stream()
                .filter(instance -> instance.view().mode() != FlywayMode.DISABLED)
                .toList();
        DatabaseSupport.Preflight answer;
        try {
            answer = session.preflight(enabled, plan.rootFirstSchema());
        } catch (MigrationRunException ex) {
            throw rewrap(ex, results);
        } catch (RuntimeException ex) {
            throw preflightFailure(null, "the batch preflight check failed", results);
        }
        if (answer == null || !consistent(enabled, answer.frozenViews())) {
            throw preflightFailure(null, INCONSISTENT_ANSWER, results);
        }
        List<NamedPlan.Instance> instances = new ArrayList<>(plan.instances());
        int next = 0;
        for (int i = 0; i < instances.size(); i++) {
            NamedPlan.Instance instance = instances.get(i);
            if (instance.view().mode() != FlywayMode.DISABLED) {
                MigrationInstanceView frozenView = answer.frozenViews().get(next++);
                instances.set(i, new NamedPlan.Instance(frozenView, instance.password(), instance.placeholders()));
            }
        }
        return new Frozen(List.copyOf(instances), answer.historyOwners());
    }

    /** True when every frozen view is its enabled view with, at most, an unknown default schema filled. */
    private static boolean consistent(List<NamedPlan.Instance> enabled, List<MigrationInstanceView> frozenViews) {
        if (frozenViews.size() != enabled.size()) {
            return false;
        }
        for (int i = 0; i < enabled.size(); i++) {
            MigrationInstanceView original = enabled.get(i).view();
            MigrationInstanceView frozen = frozenViews.get(i);
            boolean schemaKept = original.schema() == null || original.schema().equals(frozen.schema());
            if (!schemaKept || !frozen.equals(original.withSchema(frozen.schema()))) {
                return false;
            }
        }
        return true;
    }

    private static void beforeInstance(
            DatabaseSupport.Session session,
            int index,
            MigrationInstanceView view,
            List<MigrationInstanceResult> results) {
        try {
            session.beforeInstance(view);
        } catch (MigrationRunException ex) {
            markFailed(results, index, view);
            throw rewrap(ex, results);
        } catch (RuntimeException ex) {
            markFailed(results, index, view);
            throw preflightFailure(view.name(), "the pre-migration check failed", results);
        }
    }

    private static MigrationRunException preflightFailure(
            String instance, String detail, List<MigrationInstanceResult> results) {
        return NamedMigrationFailure.of(PREFLIGHT, instance, detail).toException(OP_RUN, new MigrationReport(results));
    }

    private static void markFailed(List<MigrationInstanceResult> results, int index, MigrationInstanceView view) {
        results.set(index, new MigrationInstanceResult(view, MigrationStatus.FAILED, 0, null));
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

    // --- Existing histories kept beside new ones ---

    /**
     * Validates, before any instance runs, the existing history of every instance the session named as
     * its owner: native validation against the owner's own locations, schemas and history table, with
     * the target {@code latest} and pending migrations as the only ignored state, whatever the owner's
     * own validation setting. Pending owner work passes; unresolved, failed or mismatched applied
     * migrations reject the whole batch.
     */
    private void validateHistoryOwners(Frozen frozen, List<MigrationInstanceResult> results) {
        Set<String> owners = frozen.historyOwners();
        if (owners.isEmpty()) {
            return;
        }
        List<NamedPlan.Instance> ownerInstances = frozen.instances().stream()
                .filter(instance -> instance.view().mode() != FlywayMode.DISABLED)
                .filter(instance -> owners.contains(instance.view().name()))
                .toList();
        if (ownerInstances.size() != owners.size()) {
            throw preflightFailure(null, INCONSISTENT_ANSWER, results);
        }
        for (NamedPlan.Instance instance : ownerInstances) {
            String name = instance.view().name();
            boolean agreed;
            try {
                Flyway flyway = configure(instance)
                        .target(LATEST)
                        .ignoreMigrationPatterns(PENDING_ONLY)
                        .load();
                agreed = agrees(flyway.getConfiguration(), instance.view())
                        && enforcesOwnerValidation(flyway.getConfiguration());
                if (agreed) {
                    log.info("Validating the existing history of instance {} before any instance runs", name);
                    flyway.validate();
                }
            } catch (RuntimeException ex) {
                log.warn("The existing history of instance {} did not pass validation", name);
                throw preflightFailure(
                        name,
                        "the existing history the instance keeps does not validate against its migrations"
                                + nativeCode(ex) + "; keep the single-Flyway configuration for this database",
                        results);
            }
            if (!agreed) {
                throw preflightFailure(name, DISAGREEING_CONFIGURATION, results);
            }
        }
    }

    /** True when the loaded configuration validates against the latest version and ignores only pending work. */
    private static boolean enforcesOwnerValidation(Configuration effective) {
        ValidatePattern[] patterns = effective.getIgnoreMigrationPatterns();
        return MigrationVersion.LATEST.equals(effective.getTarget())
                && patterns != null
                && Arrays.stream(patterns).map(String::valueOf).toList().equals(List.of(PENDING_ONLY));
    }

    // --- One instance ---

    /** Runs one enabled instance and records its result; returns the native target version of a migrate. */
    private String runInstance(int index, NamedPlan.Instance instance, List<MigrationInstanceResult> results) {
        MigrationInstanceView view = instance.view();
        boolean migrate = view.mode() == FlywayMode.MIGRATE;
        Flyway flyway;
        boolean agreed;
        int applied = 0;
        String targetVersion = null;
        try {
            flyway = configure(instance).load();
            agreed = agrees(flyway.getConfiguration(), view);
            if (agreed && migrate) {
                log.info("Running Flyway migration for instance {}", view.name());
                MigrateResult result = flyway.migrate();
                applied = result.migrationsExecuted;
                targetVersion = result.targetSchemaVersion;
            } else if (agreed) {
                log.info("Validating Flyway schema for instance {}", view.name());
                flyway.validate();
            }
        } catch (RuntimeException ex) {
            markFailed(results, index, view);
            String failureCode = migrate ? MIGRATION : VALIDATION;
            log.warn("Flyway {} failed for instance {} ({})", view.mode(), view.name(), failureCode);
            throw NamedMigrationFailure.of(failureCode, view.name(), failureDetail(view.mode(), ex))
                    .toException(OP_RUN, new MigrationReport(results));
        }
        if (!agreed) {
            markFailed(results, index, view);
            log.warn(
                    "Flyway {} refused for instance {}: the effective configuration disagrees",
                    view.mode(),
                    view.name());
            throw preflightFailure(view.name(), DISAGREEING_CONFIGURATION, results);
        }
        results.set(
                index,
                new MigrationInstanceResult(
                        view,
                        migrate ? MigrationStatus.MIGRATED : MigrationStatus.VALIDATED,
                        applied,
                        readCurrentVersion(flyway, view.name())));
        log.info("Flyway {} complete for instance {}: {} migrations applied", view.mode(), view.name(), applied);
        return targetVersion;
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

    /**
     * Compares the effective native identity of a loaded Flyway with the frozen view. Native Flyway
     * reports an empty schema list when only a default schema is bound and then manages exactly that
     * default schema, so the effective list is the reported list when it is not empty and otherwise the
     * default schema alone. It must equal the view's managed list or, when that is empty, its frozen
     * default schema alone; the default schema and the history table must match as well.
     *
     * @param effective the loaded native configuration
     * @param view the frozen view
     * @return whether the native configuration agrees with the view
     */
    private static boolean agrees(Configuration effective, MigrationInstanceView view) {
        String defaultSchema = effective.getDefaultSchema();
        String[] reported = effective.getSchemas() == null ? new String[0] : effective.getSchemas();
        List<String> effectiveList =
                reported.length > 0 ? List.of(reported) : defaultSchema == null ? List.of() : List.of(defaultSchema);
        List<String> expectedList =
                !view.schemas().isEmpty() ? view.schemas() : view.schema() == null ? List.of() : List.of(view.schema());
        return Objects.equals(defaultSchema, view.schema())
                && view.table().equals(effective.getTable())
                && effectiveList.equals(expectedList);
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
     * Reads the current version after a successful operation. The operation already succeeded, so a
     * failed read leaves the version unknown instead of failing the instance; the native failure is
     * neither kept nor logged.
     */
    private static String readCurrentVersion(Flyway flyway, String instance) {
        try {
            return currentVersion(flyway);
        } catch (RuntimeException ex) {
            log.warn("The current version of instance {} could not be read after the operation", instance);
            return null;
        }
    }

    /**
     * Reads the default schema of a connection opened through Flyway's own data source. Used only by the
     * single-Flyway report when no schema is configured, to report where the history really lives.
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
        return "the native " + mode + " operation failed" + nativeCode(ex);
    }

    /** Returns the safe native metadata of a failure: its Flyway error-code name and SQLState, when present. */
    private static String nativeCode(RuntimeException ex) {
        ErrorCode errorCode = errorCode(ex);
        String code = errorCode instanceof Enum<?> constant ? " (Flyway error " + constant.name() + ")" : "";
        return code + sqlState(ex);
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
            if (current instanceof SQLException sql) {
                String suffix = NamedMigrationFailure.sqlStateSuffix(sql.getSQLState());
                if (!suffix.isEmpty()) {
                    return suffix;
                }
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
