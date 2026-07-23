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
 * </ul>
 *
 * <p>Flyway migrations are located at {@code classpath:db/migration/inbox-outbox} and create the
 * {@code inbox} and {@code outbox} tables together with the {@code LISTEN/NOTIFY} trigger.
 */
package dev.vertique.inboxoutbox.postgresql;
