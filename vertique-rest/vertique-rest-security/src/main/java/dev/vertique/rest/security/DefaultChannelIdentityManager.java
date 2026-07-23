// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.channel.ChannelBinding;
import dev.vertique.security.channel.ChannelIdentityManager;
import dev.vertique.security.events.ChannelClosedEvent;
import dev.vertique.security.events.ChannelIdentityRefreshedEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Default in-memory implementation of {@link ChannelIdentityManager} for the REST security module.
 *
 * <p>Tracks live channels in a {@link ConcurrentHashMap} keyed by channel id. Each entry carries
 * the currently bound {@link SecurityContext}, the transport-owned {@link ChannelBinding}, an
 * optional expiry timer id, and the earliest {@code notAfter} instant from the context's evidence.
 *
 * <p><b>Expiry timer scheduling.</b> When the registered {@link SecurityContext} carries a
 * {@link dev.vertique.security.AuthenticationState#earliestNotAfter()} value, a raw
 * {@code Vertx.setTimer} is scheduled to fire {@link #closeChannel(String, String)} with reason
 * code {@code "IDENTITY_EXPIRED"}. Raw timers are intentional here: channel expiry is local
 * transient lifecycle timing — it does not survive restart and is not operator-visible. This
 * satisfies the scheduling rules for per-connection lifecycle timers.
 *
 * <p><b>Channel-id collision.</b> Registering the same channel id twice closes the prior binding
 * (reason code {@code "CHANNEL_CLOSED_BY_SERVER"}) and cancels its expiry timer before installing
 * the new entry. The orphaned prior binding's scope is released immediately (both
 * {@link ChannelBinding#close(String)} and {@link ChannelBinding#releaseResources()} are called
 * directly) because no {@code @OnClose} / {@code deregister} path will run for it.
 *
 * <p><b>Single-owner cleanup.</b> All post-{@code @OnClose} cleanup — emitting
 * {@link ChannelClosedEvent}, cancelling the expiry timer, removing the registry entry, and
 * releasing the binding's scope — happens in {@link #deregister(String, String)}. This is the
 * <em>only</em> place these operations run for normal peer-close and server-initiated close paths.
 *
 * <p><b>Server-initiated close.</b> {@link #closeChannel(String, String)} records a pending reason
 * and initiates the transport close via {@link ChannelBinding#close(String)}. Cleanup is deferred
 * to {@link #deregister(String, String)}, which is called by the transport's close callback after
 * the user's {@code @OnClose} has run. This guarantees that {@code @OnClose} observes an
 * authenticated {@link SecurityContext} rather than an anonymous one.
 *
 * <p><b>Idempotent operations.</b> Both {@link #closeChannel(String, String)} and
 * {@link #deregister(String, String)} are idempotent: calling on an unregistered or
 * already-deregistered channel returns {@link Future#succeededFuture()} immediately.
 *
 * @see ChannelIdentityManager
 * @see ChannelBinding
 */
@Singleton
public final class DefaultChannelIdentityManager implements ChannelIdentityManager {

    private final Map<String, ChannelEntry> registry = new ConcurrentHashMap<>();
    private final Map<String, String> pendingReasons = new ConcurrentHashMap<>();

    /**
     * Per-channel tail future used to serialize all mutating operations (register / refreshIdentity /
     * closeChannel / deregister) for a given channel id. Each operation chains after the prior one for
     * the same channel, so a channel's state transitions are totally ordered and the registry entry can
     * never desync from the transport context actually bound by {@link ChannelBinding#rebind}. Entries
     * are removed once a channel's queue drains. See {@link #serialize(String, Supplier)}.
     */
    private final Map<String, Future<Void>> channelOps = new ConcurrentHashMap<>();

    private final Vertx vertx;
    private final SecurityEventEmitter emitter;
    private final ContextHolder contextHolder;

    /**
     * Creates a new channel identity manager.
     *
     * @param vertx         the Vert.x instance used to schedule expiry timers; must not be
     *                      {@code null}
     * @param emitter       the security-event emitter used to fan out lifecycle events; must not be
     *                      {@code null}
     * @param contextHolder the context holder used to read the ambient
     *                      {@link CorrelationContext}; must not be {@code null}
     */
    @Inject
    public DefaultChannelIdentityManager(Vertx vertx, SecurityEventEmitter emitter, ContextHolder contextHolder) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
    }

    // --- ChannelIdentityManager ---

    /**
     * {@inheritDoc}
     *
     * <p>If a prior entry exists for the same {@code channelId}, its binding is closed and its
     * scope released immediately (both {@link ChannelBinding#close(String)} and
     * {@link ChannelBinding#releaseResources()} are called directly) and its expiry timer is
     * cancelled before the new entry is installed. The orphaned prior binding bypasses the normal
     * {@code deregister} path because no transport close callback / {@code @OnClose} will fire for
     * it. An expiry timer is scheduled when
     * {@link dev.vertique.security.AuthenticationState#earliestNotAfter()} is non-empty.
     */
    @Override
    public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(binding, "binding");

        return serialize(channelId, () -> {
            Optional<Instant> notAfter = ctx.authentication().earliestNotAfter();
            // Per-connection expiry timer — local transient lifecycle timing; not durable across restart.
            long timerId = scheduleExpiry(channelId, notAfter);

            ChannelEntry entry = new ChannelEntry(ctx, binding, timerId, notAfter);
            ChannelEntry prior = registry.put(channelId, entry);
            if (prior != null) {
                // Channel-id collision — the orphaned prior binding has no @OnClose / deregister path,
                // so close the transport and release its scope directly without going through deregister.
                cancelTimer(prior.timerId());
                pendingReasons.remove(channelId);
                prior.binding()
                        .close("CHANNEL_CLOSED_BY_SERVER")
                        .compose(v -> prior.binding().releaseResources())
                        .recover(t -> Future.succeededFuture());
            }

            CorrelationContext correlation = readCorrelation();
            ChannelOpenedEvent event = new ChannelOpenedEvent(Instant.now(), channelId, ctx, correlation);
            emitLifecycle(event);
            return Future.succeededFuture();
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ChannelBinding#rebind(SecurityContext)} on the registered binding,
     * then updates the registry with the new context and reschedules the expiry timer based on the
     * new context's {@link dev.vertique.security.AuthenticationState#earliestNotAfter()}.
     *
     * <p><b>Refresh is serialized per channel.</b> This operation runs through
     * {@link #serialize(String, Supplier)}, so no other register / refresh / close / deregister for
     * the same channel can interleave with it. The registry update therefore reflects exactly the
     * context just bound by {@code rebind}, and concurrent refreshes apply in submission order — the
     * registry can never desync from the transport context actually bound on the channel. If a
     * server-initiated close is already pending (see {@link #closeChannel(String, String)}), the
     * channel is terminal and the refresh fails with a failed {@link Future} rather than rebinding,
     * rescheduling expiry, or emitting a refresh event on a channel being torn down. The binding's own
     * {@link ChannelBinding#rebind(SecurityContext)} additionally fails if its scopes were already
     * released, as defence in depth for callers that bypass this manager.
     */
    @Override
    public Future<Void> refreshIdentity(String channelId, SecurityContext newCtx) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(newCtx, "newCtx");

        return serialize(channelId, () -> {
            ChannelEntry existing = registry.get(channelId);
            if (existing == null) {
                return Future.failedFuture(new IllegalStateException("Channel not registered: " + channelId));
            }
            if (pendingReasons.containsKey(channelId)) {
                // A server-initiated close/revocation has already begun; the channel is terminal.
                // Reject the refresh so the eventual ChannelClosedEvent carries the pre-close identity
                // and we do not reschedule expiry on a channel that is being torn down.
                return Future.failedFuture(
                        new IllegalStateException("Channel is closing; cannot refresh identity: " + channelId));
            }

            return existing.binding().rebind(newCtx).compose(v -> {
                Optional<Instant> newNotAfter = newCtx.authentication().earliestNotAfter();
                // Per-connection expiry timer — local transient lifecycle timing; not durable across restart.
                long newTimerId = scheduleExpiry(channelId, newNotAfter);
                ChannelEntry refreshed = new ChannelEntry(newCtx, existing.binding(), newTimerId, newNotAfter);
                cancelTimer(existing.timerId());
                // Safe to overwrite directly: per-channel serialization guarantees no register / refresh /
                // close / deregister ran between the read above and this write, so the registry entry
                // matches the context just bound by rebind().
                registry.put(channelId, refreshed);

                CorrelationContext correlation = readCorrelation();
                ChannelIdentityRefreshedEvent event = new ChannelIdentityRefreshedEvent(
                        Instant.now(), channelId, newCtx, correlation, existing.ctx());
                emitLifecycle(event);
                return Future.succeededFuture();
            });
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records {@code reasonCode} as the pending close reason for this channel, then initiates the
     * transport close via {@link ChannelBinding#close(String)}. The
     * {@link ChannelClosedEvent} emission, timer cancellation, registry removal, and scope release
     * are all deferred to {@link #deregister(String, String)}, which is called by the transport's
     * close callback after the user's {@code @OnClose} has completed.
     *
     * <p>Idempotent: calling on an unregistered or already-closed channel returns a succeeded future
     * immediately without recording a pending reason. Likewise, if a close was already initiated for
     * this channel (a pending reason is present), this is a no-op — the first reason is kept and no
     * second transport close is sent, so the eventual {@link ChannelClosedEvent} always carries the
     * reason of the close that actually ran.
     */
    @Override
    public Future<Void> closeChannel(String channelId, String reasonCode) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(reasonCode, "reasonCode");

        return serialize(channelId, () -> {
            ChannelEntry entry = registry.get(channelId);
            if (entry == null) {
                // Idempotent — closing an unknown or already-closed channel is a no-op.
                return Future.succeededFuture();
            }

            // Record the server-initiated reason so deregister() can use it after @OnClose runs. If a
            // close is already pending, keep the first reason and do not send a second transport close.
            if (pendingReasons.putIfAbsent(channelId, reasonCode) != null) {
                return Future.succeededFuture();
            }

            // Initiate transport close only — scope release and event emission happen in deregister().
            return entry.binding().close(reasonCode).recover(t -> Future.succeededFuture());
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is the single cleanup owner. Removes the entry from the registry, cancels its expiry
     * timer, determines the effective reason (pending reason from a prior
     * {@link #closeChannel(String, String)} if present, otherwise {@code fallbackReasonCode}),
     * emits {@link ChannelClosedEvent}, and calls {@link ChannelBinding#releaseResources()}.
     *
     * <p>Idempotent: if the channel is not found, returns a succeeded future immediately.
     */
    @Override
    public Future<Void> deregister(String channelId, String fallbackReasonCode) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(fallbackReasonCode, "fallbackReasonCode");

        return serialize(channelId, () -> {
            ChannelEntry entry = registry.remove(channelId);
            if (entry == null) {
                // Idempotent — already deregistered or never registered.
                return Future.succeededFuture();
            }
            cancelTimer(entry.timerId());

            // Use the pending reason from a prior server-initiated close if present; otherwise the
            // peer-close fallback supplied by the transport adapter.
            String effectiveReason = pendingReasons.remove(channelId);
            if (effectiveReason == null) {
                effectiveReason = fallbackReasonCode;
            }

            CorrelationContext correlation = readCorrelation();
            ChannelClosedEvent event =
                    new ChannelClosedEvent(Instant.now(), channelId, entry.ctx(), correlation, effectiveReason);
            emitLifecycle(event);
            // Scope release is part of deregister's contract, so it stays in the serialized op's future;
            // observer fan-out (emitLifecycle) is a side-channel and is deliberately not awaited here.
            return entry.binding().releaseResources().recover(t -> Future.succeededFuture());
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SecurityContext> current(String channelId) {
        ChannelEntry entry = registry.get(channelId);
        return Optional.ofNullable(entry).map(ChannelEntry::ctx);
    }

    // --- Helpers ---

    /**
     * Fans out a channel lifecycle event to observers <em>without</em> awaiting their completion.
     *
     * <p>Lifecycle emission happens inside a per-channel serialized operation (see
     * {@link #serialize(String, Supplier)}). An observer may legitimately react by enqueuing another
     * operation on the same channel — e.g. an {@code @OnOpen}/{@link ChannelOpenedEvent} handler that
     * returns {@code closeChannel(channelId, ...)}. That follow-up operation is queued behind the
     * current one, so if the current operation's future awaited the observer future, the two would
     * wait on each other forever. Emission is therefore fire-and-forget: the serialized operation's
     * own future never depends on observer completion. {@link SecurityEventEmitter} already isolates
     * and logs observer failures, so the discarded future carries nothing the caller needs.
     *
     * <p>The synchronous part of {@link SecurityEventEmitter#emit(ChannelLifecycleEvent)} (invoking
     * each observer's method) still runs before this returns, so observers that record events see them
     * in per-channel order.
     *
     * @param event the lifecycle event to publish; must not be {@code null}
     */
    private void emitLifecycle(ChannelLifecycleEvent event) {
        emitter.emit(event);
    }

    /**
     * Runs a mutating channel operation serialized after any prior in-flight operation for the same
     * {@code channelId}. The supplied {@code op} is invoked only after the previous operation for the
     * channel has settled (success or failure), and the next operation in turn waits for this one.
     *
     * <p>This guarantees that register / refreshIdentity / closeChannel / deregister for a given
     * channel never interleave. Because each operation both mutates the registry entry and (for
     * refresh) drives a transport rebind, serializing them is what keeps {@link #current(String)} and
     * the emitted lifecycle events consistent with the {@link SecurityContext} actually bound on the
     * channel — two concurrent refreshes can no longer apply their rebinds in one order while the
     * registry records another.
     *
     * <p>The per-channel tail entry is removed from {@link #channelOps} once its queue drains, so the
     * map stays bounded by the number of channels with operations in flight.
     *
     * @param channelId the channel whose operations are serialized; must not be {@code null}
     * @param op        supplies the operation's {@link Future}; invoked once the prior op has settled
     * @return a future completing with this operation's own result (independent of the tail used for
     *         ordering)
     */
    private Future<Void> serialize(String channelId, Supplier<Future<Void>> op) {
        Promise<Void> done = Promise.promise();
        // The next op for this channel chains after THIS op settles (success or failure), so ordering
        // holds even when an op fails. Built before compute() so it can be installed as the new tail.
        Future<Void> newTail = done.future().recover(t -> Future.succeededFuture());
        // compute() does only the bookkeeping under the map's bin lock: capture the prior tail and
        // install ours. It MUST NOT run the operation — op.get() emits lifecycle events whose observers
        // may synchronously re-enter the manager (refreshIdentity / closeChannel / deregister) and thus
        // this same map. Running op inside compute() would recursively update the same key, which
        // ConcurrentHashMap forbids. (register is always the first op for a channel, so its prior tail
        // is an already-complete future whose continuation would otherwise run inline inside compute().)
        AtomicReference<Future<Void>> prevRef = new AtomicReference<>();
        channelOps.compute(channelId, (id, tail) -> {
            prevRef.set(tail == null ? Future.succeededFuture() : tail);
            return newTail;
        });
        // Start the operation only after compute() has returned, gated on the prior op settling. A
        // re-entrant call from an observer now hits a fresh compute() (the outer one has returned) and
        // is correctly queued behind this operation rather than nesting.
        prevRef.get()
                .recover(t -> Future.succeededFuture())
                .compose(ignored -> op.get())
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        done.complete();
                    } else {
                        done.fail(ar.cause());
                    }
                });
        // Drop the entry once our op is the last to settle; the CAS-style remove leaves a newer tail in
        // place if another op was queued behind us.
        newTail.onComplete(ar -> channelOps.remove(channelId, newTail));
        return done.future();
    }

    /**
     * Schedules a Vert.x timer to call {@link #closeChannel(String, String)} with reason code
     * {@code "IDENTITY_EXPIRED"} when the given {@code notAfter} instant is reached.
     *
     * <p>Returns {@code -1L} when {@code notAfter} is empty (no timer scheduled). When
     * {@code notAfter} is already in the past the timer fires on the next event-loop tick
     * (1 ms delay) to avoid blocking the caller.
     *
     * @param channelId the channel id to close on expiry
     * @param notAfter  the expiry instant, or empty when no timer should be scheduled
     * @return the Vert.x timer id, or {@code -1L} when no timer was scheduled
     */
    private long scheduleExpiry(String channelId, Optional<Instant> notAfter) {
        if (notAfter.isEmpty()) {
            return -1L;
        }
        long delayMs = Duration.between(Instant.now(), notAfter.get()).toMillis();
        if (delayMs <= 0) {
            // Already expired — fire on the next event-loop tick.
            return vertx.setTimer(1L, id -> closeChannel(channelId, "IDENTITY_EXPIRED"));
        }
        return vertx.setTimer(delayMs, id -> closeChannel(channelId, "IDENTITY_EXPIRED"));
    }

    /**
     * Cancels a previously-scheduled Vert.x timer. No-ops when {@code timerId} is {@code -1L}
     * (sentinel for "no timer scheduled").
     *
     * @param timerId the timer id to cancel, or {@code -1L} if no timer was scheduled
     */
    private void cancelTimer(long timerId) {
        if (timerId > 0) {
            vertx.cancelTimer(timerId);
        }
    }

    /**
     * Reads the ambient {@link CorrelationContext} from the context holder.
     *
     * @return the current correlation context
     * @throws IllegalStateException if no {@link CorrelationContext} is bound in the current
     *                               Vert.x context
     */
    private CorrelationContext readCorrelation() {
        return contextHolder
                .current(CorrelationContext.class)
                .orElseThrow(() -> new IllegalStateException(
                        "CorrelationContext must be bound before ChannelIdentityManager methods are called"));
    }

    // --- Internal record ---

    /**
     * Immutable registry entry holding all per-channel state.
     *
     * @param ctx      the currently bound {@link SecurityContext}
     * @param binding  the transport-owned callback for rebind and close operations
     * @param timerId  the Vert.x timer id for auto-expiry, or {@code -1L} when no timer is active
     * @param notAfter the expiry instant used to schedule the timer, or empty when absent
     */
    private record ChannelEntry(
            SecurityContext ctx, ChannelBinding binding, long timerId, Optional<Instant> notAfter) {}
}
