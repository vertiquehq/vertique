// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.MigrationException;

/**
 * Failure of a named-instance migration operation.
 *
 * <p>The message is written by the framework and names the operation and, when one is to blame, the
 * instance. It never contains SQL, JDBC URLs, credentials or placeholder values, and the exception
 * never retains the original third-party throwable as its cause or as a suppressed exception,
 * because native exceptions can carry statements and connection details.
 *
 * <p>{@link #failureCode()} is a stable category from the closed set {@code CONFIGURATION},
 * {@code RESOURCE}, {@code COORDINATION}, {@code PREFLIGHT}, {@code MIGRATION} and
 * {@code VALIDATION}. {@link #operation()} names the failed step, for example {@code run},
 * {@code instances}, {@code coordination} or {@code configuration}. {@link #report()} is the ordered
 * report of a failed run, or {@code null} when the failure happened before any report existed (while
 * resolving instances). A configuration that cannot be bound never reaches this type; it fails earlier
 * with the framework's {@link dev.vertique.core.exception.ConfigurationException}.
 */
public final class MigrationRunException extends MigrationException {

    private final String failureCode;
    private final String operation;
    private final MigrationReport report;

    /**
     * Creates a named migration failure without a cause.
     *
     * @param message the framework-authored message
     * @param failureCode the stable failure category
     * @param operation the failed operation
     * @param report the ordered report, or {@code null} when none exists
     */
    public MigrationRunException(String message, String failureCode, String operation, MigrationReport report) {
        super(message, null);
        this.failureCode = failureCode;
        this.operation = operation;
        this.report = report;
    }

    /**
     * Returns the stable failure category.
     *
     * @return the failure code
     */
    public String failureCode() {
        return failureCode;
    }

    /**
     * Returns the operation that failed.
     *
     * @return the operation name
     */
    public String operation() {
        return operation;
    }

    /**
     * Returns the ordered report of the failed run.
     *
     * @return the report, or {@code null} when the failure happened before any report existed
     */
    public MigrationReport report() {
        return report;
    }
}
