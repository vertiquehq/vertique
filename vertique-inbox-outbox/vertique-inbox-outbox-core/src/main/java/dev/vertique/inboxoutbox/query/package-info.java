// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Read access to outbox entries.
 *
 * <p>{@link dev.vertique.inboxoutbox.query.OutboxQueryService} lets trusted in-process code, such as
 * administration adapters, list outbox entries by state and other filters, read one entry and read its stored
 * payload. The package holds the service, the immutable query and result types, and
 * {@link dev.vertique.inboxoutbox.query.OutboxQueryConfig}. Changing an entry is
 * {@link dev.vertique.inboxoutbox.ops.OutboxOperations}.
 *
 * <p>The service performs no authorization. It filters on an entry's scope key only when
 * {@link dev.vertique.inboxoutbox.query.OutboxQuery#visibleTo()} is set and returns it as a label. Stored carrier, security and context metadata are never returned.
 */
package dev.vertique.inboxoutbox.query;
