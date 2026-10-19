// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.Objects;

/**
 * Outcome of one migration instance.
 *
 * <p>{@code currentVersion} is the instance's version read through Flyway after the operation, not
 * the version applied by this invocation; it is {@code null} when no versioned migration has been
 * applied, and for failed, not-run and disabled entries. {@code migrationsApplied} is zero for those
 * entries too.
 *
 * @param instance the resolved instance; for an attempted entry its schema is the history schema
 *     actually used
 * @param status the outcome
 * @param migrationsApplied the number of migrations this invocation applied
 * @param currentVersion the current version after the operation, or {@code null}
 */
public record MigrationInstanceResult(
        MigrationInstanceView instance, MigrationStatus status, int migrationsApplied, String currentVersion) {

    /**
     * Creates a result.
     *
     * @throws NullPointerException if {@code instance} or {@code status} is {@code null}
     */
    public MigrationInstanceResult {
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
