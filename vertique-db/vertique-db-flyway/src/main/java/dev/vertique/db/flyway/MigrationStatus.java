// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

/** Outcome of one migration instance in a {@link MigrationReport}. */
public enum MigrationStatus {

    /** Flyway migrated the instance; zero or more migrations were applied. */
    MIGRATED,

    /** Flyway validated the instance without applying anything. */
    VALIDATED,

    /** The instance's effective mode is {@link FlywayMode#DISABLED}; nothing connected for it. */
    DISABLED,

    /** The instance was attempted and failed; later instances were not attempted. */
    FAILED,

    /** The instance was not attempted because the run stopped earlier. */
    NOT_RUN
}
