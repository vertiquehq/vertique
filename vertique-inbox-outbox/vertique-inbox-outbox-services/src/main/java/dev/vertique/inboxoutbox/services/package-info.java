// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * SERVICE destination adapter for the transactional outbox pattern.
 *
 * <p>This package bridges the outbox relay pipeline to the Vert.x event bus services
 * framework. It provides two complementary capabilities:
 *
 * <ul>
 *   <li>{@link dev.vertique.inboxoutbox.services.TransactionalServiceClientFactory} —
 *       creates transaction-scoped JDK proxies that authors use when writing outbox entries
 *       for service operations. Callers use the proxy as if it were a normal service client;
 *       the factory captures the payload and operation identity into an
 *       {@link dev.vertique.inboxoutbox.OutboxEntry} instead of dispatching immediately
 *       over the event bus.</li>
 *   <li>{@link dev.vertique.inboxoutbox.services.ServiceOutboxDestinationHandler} —
 *       the {@link dev.vertique.inboxoutbox.OutboxDestinationHandler} implementation for
 *       {@link dev.vertique.inboxoutbox.DestinationType#SERVICE} entries. At relay time it
 *       resolves the stable target id back to a live event bus address and forwards the
 *       payload via {@link dev.vertique.services.ServiceRequestSender}, injecting a
 *       {@link dev.vertique.inboxoutbox.TransactionalMessageContext} into the dispatch
 *       context so that handlers can access message metadata.</li>
 * </ul>
 *
 * <p>Register this adapter by including
 * {@link dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule} in your
 * Dagger component alongside
 * {@link dev.vertique.inboxoutbox.TransactionalMessagingModule}.
 */
package dev.vertique.inboxoutbox.services;
