// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL-backed persistence for the transactional messaging (inbox/outbox) module.
 *
 * <p>This package provides:
 * <ul>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository} — implements both
 *       {@link dev.vertique.inboxoutbox.InboxRepository} and
 *       {@link dev.vertique.inboxoutbox.OutboxRepository} against a PostgreSQL database using
 *       {@code FOR UPDATE SKIP LOCKED} claim semantics and {@code ON CONFLICT DO NOTHING} for
 *       inbox deduplication</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.OutboxRecordMapper} — maps PostgreSQL
 *       {@code Row} instances to {@link dev.vertique.inboxoutbox.OutboxRecord} domain objects</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.DefaultInboxService} — default implementation
 *       of {@link dev.vertique.inboxoutbox.InboxService}</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.DefaultOutboxService} — default implementation
 *       of {@link dev.vertique.inboxoutbox.OutboxService}</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.OutboxRelay} — Vert.x verticle that claims
 *       pending outbox entries, delivers them via registered
 *       {@link dev.vertique.inboxoutbox.OutboxDestinationHandler}s, and handles retry/dead-letter
 *       lifecycle; supports both {@link dev.vertique.inboxoutbox.RelayStrategy#POLLING} and
 *       {@link dev.vertique.inboxoutbox.RelayStrategy#LISTEN_NOTIFY}</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule} — Dagger
 *       module that binds repositories, services, and deploys the relay verticle</li>
 *   <li>{@link dev.vertique.inboxoutbox.postgresql.InboxOutboxMigrationsModule} — Dagger module
 *       that contributes the {@code inbox-outbox} migration declaration for named Flyway
 *       instances</li>
 * </ul>
 *
 * <p>Flyway migrations are located at {@code classpath:db/migration/inbox-outbox} and create the
 * {@code inbox} and {@code outbox} tables together with the {@code LISTEN/NOTIFY} trigger.
 *
 * <p><b>Schema placement.</b> The {@code inbox-outbox} name is both the migration instance and the key of
 * the optional {@code db.schemas} mapping. When a {@link dev.vertique.db.DbSchemas} binding maps it to a schema, the
 * migration places the tables, indexes, sequence, notify function and trigger in that schema, and
 * {@link dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository} qualifies every inbox and outbox
 * table reference with it when the repository is constructed. A mapped schema that is not a single
 * valid PostgreSQL identifier fails at construction. Without a mapping the SQL is unqualified and
 * resolves through the connection's default schema, as before.
 *
 * <p>{@link dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule} builds the
 * repository from an optional {@code DbSchemas} binding, so a component that installs
 * {@code DbModule} places the tables from {@code db.schemas.inbox-outbox} without any Flyway module.
 * A component that also provides {@code PgInboxOutboxRepository} itself sees a duplicate binding when it
 * installs that module; the {@code @Inject} constructor without a schema argument remains for
 * components that do not. The runtime database role needs {@code USAGE} on the mapped schema. The
 * {@code transactional_outbox_channel} notification channel stays database-wide and is not a tenant
 * boundary.
 */
package dev.vertique.inboxoutbox.postgresql;
