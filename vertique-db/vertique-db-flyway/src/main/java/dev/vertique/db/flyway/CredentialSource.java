// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

/**
 * Where the migration credentials of a named Flyway instance come from. The user and the password
 * are always taken together from one level, never mixed across levels.
 */
public enum CredentialSource {

    /** The instance's own {@code user} and {@code password} override. */
    INSTANCE,

    /** The root {@code flyway.user} and {@code flyway.password} pair. */
    ROOT_FLYWAY,

    /** The {@code db.user} and {@code db.password} pair of the pool configuration. */
    DB_POOL
}
