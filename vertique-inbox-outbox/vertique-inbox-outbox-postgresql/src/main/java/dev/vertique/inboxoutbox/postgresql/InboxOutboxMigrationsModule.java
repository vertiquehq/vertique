// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.db.MigrationDefinition;
import java.util.List;

/**
 * Dagger module that contributes the inbox and outbox tables' {@link MigrationDefinition} to the
 * {@code Set<MigrationDefinition>} that a migration runner consumes.
 *
 * <p>The definition is metadata only: it names the {@code inbox-outbox} instance and points at the
 * packaged inbox/outbox migration scripts on the classpath
 * ({@code classpath:db/migration/inbox-outbox}). It declares no dependencies, opens no connection
 * and needs no database bindings, so a migration-only component can include it without a
 * connection pool.
 *
 * <p>The instance name {@code inbox-outbox} is also the key under which an application maps the
 * inbox and outbox tables to a schema in its {@code db.schemas} setting; the migrations and
 * {@link PgInboxOutboxRepository} then place and read both tables in that one schema.
 *
 * <p>{@link TransactionalMessagingPostgresqlModule} includes this module, so an application that
 * wires the inbox/outbox repository contributes the definition automatically. A component that
 * only runs migrations can include this module on its own.
 */
@Module
public abstract class InboxOutboxMigrationsModule {

    /** Name of the inbox/outbox migration instance and key of its schema mapping. */
    static final String INSTANCE_NAME = "inbox-outbox";

    /** Classpath location of the packaged inbox/outbox migration scripts. */
    private static final String LOCATION = "classpath:db/migration/inbox-outbox";

    /**
     * Contributes the inbox/outbox migration declaration.
     *
     * @return the constant definition for the {@code inbox-outbox} instance, with no dependencies
     */
    @Provides
    @IntoSet
    static MigrationDefinition inboxOutboxMigrationDefinition() {
        return new MigrationDefinition(INSTANCE_NAME, List.of(LOCATION), List.of());
    }
}
