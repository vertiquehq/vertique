// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.postgresql;

import dev.vertique.starter.postgresql.PostgresqlPersistenceModule;

/**
 * Production-source compile consumer marker for {@link PostgresqlPersistenceModule}.
 *
 * <p>Unlike the core, REST, and services fixtures, this consumer is deliberately <em>not</em> a
 * {@code @VertiqueApp} component: the PostgreSQL starter is an independent capability, not an
 * application foundation. It supplies no lifecycle, so nothing in these production sources can — or
 * should — compose an application.
 *
 * <p>What this class proves is precisely that independence: the fixture's only production dependency
 * is {@code vertique-starter-postgresql}, so the aggregate is referenced and compiled with no
 * application framework on the compile classpath at all. Graph composition with an application
 * starter is proven separately, at test scope, by {@code PostgresqlStarterConsumerTest}.
 */
public final class PostgresqlStarterConsumer {

    /**
     * The persistence aggregate an application names in its {@code @Component} alongside an
     * application starter. Referencing the class here is the compile-scope consumability proof.
     */
    public static final Class<?> PERSISTENCE_MODULE = PostgresqlPersistenceModule.class;

    /** Not instantiable: this marker exists only to reference the aggregate at compile scope. */
    private PostgresqlStarterConsumer() {}
}
