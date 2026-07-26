// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Independent PostgreSQL persistence starter.
 *
 * <p>This package holds a single public Dagger aggregate,
 * {@link dev.vertique.starter.postgresql.PostgresqlPersistenceModule}, that composes the PostgreSQL
 * connection pool with the application-owned Flyway migration wiring. Applications name the
 * aggregate in their {@code @Component} alongside an application starter instead of repeating the
 * three database modules.
 *
 * <p>The starter is an independent capability, not an application foundation: it supplies no core
 * application lifecycle, REST surface, management surface, deployment entry, launcher, test
 * library, or generated module. Migration content and migration policy remain application-owned.
 */
package dev.vertique.starter.postgresql;
