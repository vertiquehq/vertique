// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dev.vertique.core.exception.VertiqueException;
import jakarta.annotation.Nullable;

/**
 * Thrown when a database migration operation fails.
 *
 * <p>Wraps vendor-specific migration exceptions (e.g., Flyway) to prevent them from leaking into
 * application code. May carry a partial {@link MigrationResult} if the migration engine reported
 * progress before failing.
 *
 * <p>Example usage in a {@link MigrationRunner} implementation:
 *
 * <pre>{@code
 * try {
 *     MigrateResult result = flyway.migrate();
 *     return new MigrationResult(result.migrationsExecuted, result.targetSchemaVersion);
 * } catch (FlywayException e) {
 *     throw new MigrationException("Migration failed: " + e.getMessage(), e);
 * }
 * }</pre>
 */
public class MigrationException extends VertiqueException {

    @Nullable
    private final MigrationResult partialResult;

    /**
     * Constructs a migration exception with a message and cause.
     *
     * @param message human-readable description of what went wrong
     * @param cause   the underlying vendor exception
     */
    public MigrationException(String message, Throwable cause) {
        super(message, cause);
        this.partialResult = null;
    }

    /**
     * Constructs a migration exception with a message, cause, and a partial result captured before
     * the failure occurred.
     *
     * @param message       human-readable description of what went wrong
     * @param cause         the underlying vendor exception
     * @param partialResult the partial {@link MigrationResult} available before the failure, or
     *                      {@code null} if none
     */
    public MigrationException(String message, Throwable cause, @Nullable MigrationResult partialResult) {
        super(message, cause);
        this.partialResult = partialResult;
    }

    /**
     * Returns the partial migration result that was captured before the failure, if any.
     *
     * @return a {@link MigrationResult} representing migrations applied before failure, or
     *         {@code null} if none was captured
     */
    @Nullable
    public MigrationResult partialResult() {
        return partialResult;
    }
}
