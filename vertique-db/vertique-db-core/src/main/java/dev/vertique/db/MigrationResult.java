// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

/**
 * Result of a schema migration run.
 *
 * @param migrationsApplied number of migrations that were applied
 * @param targetVersion     the version after migration, or {@code null} if no migrations were
 *                          applied
 */
public record MigrationResult(int migrationsApplied, String targetVersion) {}
