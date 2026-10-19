// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.MigrationException;

/**
 * Failure of a named-instance migration operation.
 *
 * <p>The message is a fixed sentence written by the framework. It names the stage, the instance when
 * one is to blame, and, for a native Flyway failure, the Flyway error-code name and the SQLState when
 * the failure carries them. It never contains a statement, JDBC URL, credential or placeholder value.
 *
 * <p>For a native failure the original Flyway exception is kept as the cause, as in the
 * single-Flyway path. Flyway messages can embed statement text, so log {@link #getMessage()} and
 * {@link #stage()}, not the full cause chain.
 *
 * <p>{@link #report()} is the ordered report of a failed run, or {@code null} when the failure
 * happened before any report existed (while resolving instances). A configuration that cannot be bound
 * never reaches this type; it fails earlier with the framework's
 * {@link dev.vertique.core.exception.ConfigurationException}.
 */
public final class MigrationRunException extends MigrationException {

    /** The step of a named operation that failed. */
    public enum Stage {

        /** The named configuration or the migration declarations are invalid. */
        CONFIGURATION,

        /** A native migrate operation failed. */
        MIGRATION,

        /** A native validate operation failed. */
        VALIDATION
    }

    private final Stage stage;
    private final MigrationReport report;

    /**
     * Creates a named migration failure.
     *
     * @param message the framework-authored message
     * @param cause the native cause, or {@code null} when the framework itself rejected the input
     * @param stage the failed stage
     * @param report the ordered report, or {@code null} when none exists
     */
    public MigrationRunException(String message, Throwable cause, Stage stage, MigrationReport report) {
        super(message, cause);
        this.stage = stage;
        this.report = report;
    }

    /**
     * Returns the stage that failed.
     *
     * @return the stage
     */
    public Stage stage() {
        return stage;
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
