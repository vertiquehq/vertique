// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter.postgresql;

/**
 * Composes PostgreSQL pooling and application-owned Flyway migration for Vertique applications.
 *
 * <p>Membership is exactly {@link dev.vertique.db.DbModule},
 * {@link dev.vertique.db.postgresql.DbPostgresqlModule}, and
 * {@link dev.vertique.db.flyway.DbFlywayModule}. This membership and the module's direct dependency
 * ledger are release-line compatibility surfaces.
 *
 * <p>This independent capability does not supply the core application lifecycle, REST,
 * management, deployment entries, a launcher, test libraries, or generated modules. Applications
 * compose it with an application starter and own their migrations.
 */
@dagger.Module(
        includes = {
            dev.vertique.db.DbModule.class,
            dev.vertique.db.postgresql.DbPostgresqlModule.class,
            dev.vertique.db.flyway.DbFlywayModule.class
        })
public abstract class PostgresqlPersistenceModule {}
