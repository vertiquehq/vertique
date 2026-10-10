// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dev.vertique.core.extension.OrderedExtension;

/**
 * SPI for observing every outbox publish attempt, of every destination type, at the relay.
 *
 * <p>The relay calls {@link #onPublishCompleted} exactly once per claimed entry per attempt, after
 * it has asked the repository to record what happens to the entry next and that call has settled.
 * The observer therefore sees both how the attempt ended and what the relay did with the entry —
 * published, retry scheduled, dead-lettered or deferred — and whether the repository confirmed it.
 *
 * <h2>Observer-only contract</h2>
 *
 * <p>An observer cannot change the state transition of the entry, the relay's in-flight count or
 * its poll loop: the callback runs after all three are decided. A {@link LinkageError} or
 * {@link AssertionError} thrown by an observer is swallowed the same way as an exception, and the
 * observers after it still run.
 *
 * <h2>Limits</h2>
 *
 * <p>There is no notification for an attempt whose handler future never settles, whose repository
 * call never completes, or that is in flight when the relay stops and never completes. An entry
 * whose claim goes stale is reclaimed and attempted again, and that later attempt is notified.
 *
 * <h2>Threading</h2>
 *
 * <p>The callback runs where the repository call for the attempt completes; with the framework's
 * repository that is the relay's event-loop context. Do not block in it. Several relay instances
 * may call the same observer concurrently.
 *
 * <h2>Ordering</h2>
 *
 * <p>Observers are ordered using the {@link OrderedExtension} contract: phase first, then ascending
 * {@link #priority()} (lower runs first within a phase), then {@link #orderKey()} as a stable
 * tie-break. Register via Dagger multibinding ({@code @IntoSet}).
 *
 * @see OutboxPublishCompletedEvent
 */
public interface OutboxPublishObserver extends OrderedExtension {

    /**
     * Called once per publish attempt, after the repository call that records the attempt has
     * settled.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param event    the facts of the completed attempt; never {@code null}
     * @param envelope the relay-built envelope of the entry, the one given to the destination
     *                 handler; also present when no handler is registered for the destination type;
     *                 never {@code null}
     */
    default void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {}
}
