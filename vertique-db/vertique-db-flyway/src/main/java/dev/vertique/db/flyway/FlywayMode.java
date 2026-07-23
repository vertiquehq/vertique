// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

/**
 * Flyway operation mode.
 *
 * <ul>
 *   <li>{@link #MIGRATE} — run pending migrations (CI/CD pipeline with DDL user)</li>
 *   <li>{@link #VALIDATE} — verify schema matches migrations, fail if not (app runtime)</li>
 *   <li>{@link #DISABLED} — no-op, skip migration entirely</li>
 * </ul>
 */
public enum FlywayMode {
    MIGRATE,
    VALIDATE,
    DISABLED
}
