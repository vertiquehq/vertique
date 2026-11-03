// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Administration API for dead-lettered outbox entries.
 *
 * <p>{@link dev.vertique.inboxoutbox.admin.OutboxDeadLetterService} lets trusted in-process code, such
 * as administration adapters, list and inspect dead-lettered outbox entries and retry or discard them.
 * The package holds the facade, the immutable query, result and command types, the semantic
 * exceptions, and {@link dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig}.
 *
 * <p>The facade performs no authorization and takes the acting identity as an explicit argument. It
 * administers the configured store as a whole: it neither filters on nor returns an entry's scope key,
 * so it is a platform-operator surface, not a scope-isolation boundary. Stored carrier, security and
 * context metadata are never returned.
 */
package dev.vertique.inboxoutbox.admin;
