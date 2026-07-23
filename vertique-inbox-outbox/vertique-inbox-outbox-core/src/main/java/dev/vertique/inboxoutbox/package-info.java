// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Transactional Messaging (Inbox/Outbox) core APIs.
 *
 * <p>Provides {@link dev.vertique.inboxoutbox.InboxService} for transactional inbound
 * deduplication and {@link dev.vertique.inboxoutbox.OutboxService} for transactional
 * outbound side-effect recording, plus the relay state model, destination handler SPI,
 * and configuration types.
 */
package dev.vertique.inboxoutbox;
