// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Operator commands on outbox entries.
 *
 * <p>{@link dev.vertique.inboxoutbox.ops.OutboxOperations} lets trusted in-process code, such as administration
 * adapters, retry a dead-lettered or discarded entry and discard a pending or dead-lettered one. Every command
 * names the state the caller saw, and a mismatch fails with
 * {@link dev.vertique.inboxoutbox.ops.OutboxEntryStateException}. Reading entries is
 * {@link dev.vertique.inboxoutbox.query.OutboxQueryService}.
 *
 * <p>The service performs no authorization and takes no actor.
 */
package dev.vertique.inboxoutbox.ops;
