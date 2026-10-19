// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Internal carrier of a named-resolution failure. It records the failure category, the instance to
 * blame and a fixed detail, and turns into the public {@link MigrationRunException} only at the
 * operation boundary, where the operation name and the report are known. It never escapes the
 * package and carries no cause.
 */
final class NamedMigrationFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final String CONFIGURATION = "CONFIGURATION";
    static final String RESOURCE = "RESOURCE";
    static final String COORDINATION = "COORDINATION";
    static final String PREFLIGHT = "PREFLIGHT";
    static final String MIGRATION = "MIGRATION";
    static final String VALIDATION = "VALIDATION";

    static final String OP_CONFIGURATION = "configuration";
    static final String OP_INSTANCES = "instances";
    static final String OP_RUN = "run";
    static final String OP_COORDINATION = "coordination";

    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private final String failureCode;
    private final String instance;
    private final transient List<MigrationInstanceView> views;
    private final transient MigrationRunException backendFailure;

    private NamedMigrationFailure(
            String failureCode,
            String instance,
            String detail,
            List<MigrationInstanceView> views,
            MigrationRunException backendFailure) {
        super(detail, null, false, false);
        this.failureCode = failureCode;
        this.instance = instance;
        this.views = views;
        this.backendFailure = backendFailure;
    }

    /**
     * Creates a failure raised before the instance views exist.
     *
     * @param failureCode the failure category
     * @param instance the instance to blame, or {@code null}
     * @param detail the fixed, value-free detail
     * @return the failure
     */
    static NamedMigrationFailure of(String failureCode, String instance, String detail) {
        return new NamedMigrationFailure(failureCode, instance, detail, null, null);
    }

    /**
     * Creates a failure raised after the instance views were resolved.
     *
     * @param failureCode the failure category
     * @param instance the instance to blame, or {@code null}
     * @param detail the fixed, value-free detail
     * @param views the resolved views in execution order
     * @return the failure
     */
    static NamedMigrationFailure withViews(
            String failureCode, String instance, String detail, List<MigrationInstanceView> views) {
        return new NamedMigrationFailure(failureCode, instance, detail, List.copyOf(views), null);
    }

    /**
     * Wraps a rejection reported by the database support after the views were resolved.
     *
     * @param backendFailure the rejection
     * @param views the resolved views in execution order
     * @return the failure
     */
    static NamedMigrationFailure fromBackend(MigrationRunException backendFailure, List<MigrationInstanceView> views) {
        return new NamedMigrationFailure(
                backendFailure.failureCode(), null, backendFailure.getMessage(), List.copyOf(views), backendFailure);
    }

    /**
     * Returns the resolved views, or {@code null} when the failure happened before they existed.
     *
     * @return the views or {@code null}
     */
    List<MigrationInstanceView> views() {
        return views;
    }

    /**
     * Converts this failure into the public exception of an operation.
     *
     * @param operation the operation that failed
     * @param report the ordered report, or {@code null} when none exists
     * @return the public exception
     */
    MigrationRunException toException(String operation, MigrationReport report) {
        if (backendFailure != null) {
            return reissue(backendFailure, operation, report);
        }
        return new MigrationRunException(message(operation, instance, getMessage()), failureCode, operation, report);
    }

    /**
     * Re-issues a database-support rejection under the given operation and report.
     *
     * @param backend the rejection
     * @param operation the operation that failed
     * @param report the ordered report, or {@code null} when none exists
     * @return the public exception
     */
    static MigrationRunException reissue(MigrationRunException backend, String operation, MigrationReport report) {
        return new MigrationRunException(
                message(operation, null, backend.getMessage()), backend.failureCode(), operation, report);
    }

    /**
     * Formats a SQLState for a framework-authored detail.
     *
     * @param state the reported SQLState, or {@code null}
     * @return {@code " (SQLState XXXXX)"} for a well-formed five-character state, else an empty string
     */
    static String sqlStateSuffix(String state) {
        return state != null && SQL_STATE.matcher(state).matches() ? " (SQLState " + state + ")" : "";
    }

    /**
     * Builds the framework-authored message of a named failure.
     *
     * @param operation the failed operation
     * @param instance the instance to blame, or {@code null}
     * @param detail the fixed, value-free detail
     * @return the message
     */
    static String message(String operation, String instance, String detail) {
        return "Named migration " + operation + " failed"
                + (instance == null ? "" : " for instance '" + instance + "'")
                + ": " + detail;
    }
}
