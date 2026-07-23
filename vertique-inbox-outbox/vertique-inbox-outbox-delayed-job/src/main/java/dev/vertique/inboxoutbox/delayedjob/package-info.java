// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Delayed-job adapter for the Transactional Messaging outbox.
 *
 * <p>Provides {@link dev.vertique.inboxoutbox.delayedjob.TransactionalDelayedJobPublisher} for
 * recording typed delayed-job side effects in the outbox table within a database transaction, and
 * {@link dev.vertique.inboxoutbox.delayedjob.DelayedJobOutboxDestinationHandler} for relaying those
 * entries to the delayed-job queue during the outbox relay phase.
 *
 * <p>Wire this module via {@link dev.vertique.inboxoutbox.delayedjob.TransactionalMessagingDelayedJobModule}
 * in your Dagger component.
 */
package dev.vertique.inboxoutbox.delayedjob;
