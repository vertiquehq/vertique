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
 * its poll loop: the callback runs after all three are decided. A {@link LinkageError},
 * {@link AssertionError} or {@link StackOverflowError} thrown by an observer is swallowed the same
 * way as an exception, and the observers after it still run. A swallowed failure is logged by class
 * name, with the failure itself at debug level only; a {@link LinkageError} is logged at error level
 * at a limited rate per observer class.
 *
 * <h2>The envelope is shared</h2>
 *
 * <p>The destination handler and every observer receive the same {@link OutboxEnvelope} instance.
 * Its header map is an unmodifiable copy, but its payload object is not copied: an observer MUST NOT
 * modify it. The envelope carries the entry's payload, its application headers and its durable
 * context, which can include an identity snapshot, and its {@code toString()} prints all three — so
 * an observer MUST NOT log the envelope, and MUST NOT put it or anything read from it into an
 * exception it throws.
 *
 * <p>An implementation MUST NOT block, and MUST NOT keep the envelope after the callback returns;
 * an observer that needs a value later copies that value while the callback runs.
 *
 * <h2>Limits</h2>
 *
 * <p>There is no notification for an attempt whose handler future never settles, whose repository
 * call never completes, or that is in flight when the relay stops and never completes. Nor is there
 * one for a claimed entry whose envelope the relay could not build: no attempt was made, and the
 * entry stays claimed until stale-lease recovery. An entry whose claim goes stale is reclaimed and
 * attempted again, and that later attempt is notified.
 *
 * <h2>Threading</h2>
 *
 * <p>The callback runs on the relay's context — the event-loop context of the relay verticle
 * instance that made the attempt — whichever thread completes the repository call. Do not block in
 * it. Several relay instances, each on its own context, may call the same observer concurrently.
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
     * Called once per publish attempt, on the relay's context, after the repository call that
     * records the attempt has settled.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation. A {@link LinkageError} and an {@link AssertionError} are
     * contained the same way, and so is a {@link StackOverflowError}; a {@link LinkageError} is
     * reported at error level at a limited rate.
     *
     * <p>An implementation MUST NOT block, MUST NOT modify or log the envelope, and MUST NOT keep
     * the envelope after returning.
     *
     * @param event    the facts of the completed attempt; never {@code null}
     * @param envelope the relay-built envelope of the entry, the same instance given to the
     *                 destination handler and to every other observer; also present when no handler
     *                 is registered for the destination type; never {@code null}
     */
    default void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {}
}
