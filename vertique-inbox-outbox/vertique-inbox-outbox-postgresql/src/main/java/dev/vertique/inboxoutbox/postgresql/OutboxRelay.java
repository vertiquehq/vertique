// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxBackoff;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntryDisposition;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishCompletedEvent;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxPublishOutcome;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x verticle that polls the outbox table, claims eligible pending entries, and delivers
 * them to their configured destinations via registered {@link OutboxDestinationHandler}s.
 *
 * <p>The relay supports two detection strategies controlled by {@link OutboxRelayConfig#strategy()}:
 * <ul>
 *   <li>{@link RelayStrategy#POLLING} — a recurring one-shot timer wakes up every
 *       {@link OutboxRelayConfig#pollingIntervalMs()} milliseconds and claims a batch</li>
 *   <li>{@link RelayStrategy#LISTEN_NOTIFY} — a dedicated PostgreSQL connection listens on
 *       {@code transactional_outbox_channel} for low-latency wake-ups; polling is retained as a
 *       safety net in case the LISTEN connection is lost or slow to reconnect</li>
 * </ul>
 *
 * <p><b>Adaptive backoff:</b> When a poll cycle returns no records, the poll delay doubles up
 * to four times the base interval (capped at 60 seconds). The delay resets when entries are found.
 *
 * <p><b>Concurrency control:</b> An {@link AtomicInteger} tracks the number of in-flight
 * entries across all handlers. When the in-flight count reaches {@link OutboxRelayConfig#batchSize()},
 * claim cycles are skipped. Each entry occupies one slot from the moment the relay starts to
 * process it until the relay has asked the repository to record its outcome; the slot does not wait
 * for that repository call to settle. An entry whose envelope cannot be built, or whose processing
 * is left by an {@link Error} the relay does not handle, gives its slot back at once. An entry whose
 * handler future never settles keeps its slot.
 *
 * <p><b>Poll loop:</b> every poll cycle schedules the next one, whatever happens in it: a claim
 * that throws, returns {@code null} or completes with a {@code null} list, and a record whose
 * processing throws, all leave the loop running.
 *
 * <p><b>Publish observers:</b> After each attempt the relay asks the repository to record what
 * happens to the entry next. When that call has settled — or failed, or thrown — every registered
 * {@link OutboxPublishObserver} is notified once, in {@link OrderedExtension#comparator()} order,
 * with the facts of the attempt and the envelope the relay built. The in-flight slot is released and
 * the next poll is scheduled without waiting for the repository call; only the notification waits
 * for it. Observers are always called on this verticle's own context: when the repository call
 * settles on another context or thread, the relay hops back before it notifies. An observer that
 * throws changes neither the state transition nor the relay loop.
 *
 * <p><b>Stale lease recovery and cleanup</b> are no longer owned by this verticle — they run as
 * cluster-singleton cron jobs in
 * {@code OutboxMaintenanceCron} (see
 * {@code dev.vertique.inboxoutbox.postgresql.maintenance}). The verticle keeps only per-node
 * data-plane mechanics (poll loop and LISTEN/NOTIFY wakeup) per the framework's scheduling rule.
 *
 * <p><b>Graceful shutdown:</b> On {@link #stop(Promise)}, the poll timer is cancelled and any
 * open LISTEN connection is closed. In-flight delivery attempts are not interrupted — they
 * complete independently but their outcome callbacks may run after the verticle is stopped.
 *
 * <p><b>Threading model:</b> Each verticle instance runs on a single Vert.x event loop context.
 * Timer callbacks, event bus handlers, and the in-flight counter are single-threaded within one
 * instance. Do not deploy with {@code ThreadingModel.WORKER}.
 */
@Slf4j
public class OutboxRelay extends AbstractVerticle {

    // --- Constants ---

    /** PostgreSQL LISTEN channel name; must match the trigger DDL. */
    private static final String NOTIFY_CHANNEL = "transactional_outbox_channel";

    /** Unresolvable entries are backed off for 60 seconds before re-claiming. */
    private static final Duration UNRESOLVABLE_BACKOFF = Duration.ofSeconds(60);

    // --- Dependencies ---

    private final OutboxRelayConfig config;
    private final OutboxRepository outboxRepository;
    private final Map<DestinationType, OutboxDestinationHandler> handlerMap;
    private final RelayCapabilities capabilities;
    private final PgConnectOptions connectOptions;
    private final String nodeId;

    /** Registered publish observers, sorted once at construction (observer-only). */
    private final List<OutboxPublishObserver> observers;

    // --- Runtime state ---

    /**
     * This verticle's own context, captured at start; observers are notified on it. {@code null}
     * until the verticle is started.
     */
    private volatile Context relayContext;

    /** Observer classes already reported as unusable. */
    private final Set<Class<?>> reportedUnusable = ConcurrentHashMap.newKeySet();

    /** Current adaptive poll delay in milliseconds. */
    private long currentPollDelay;

    private long pollTimerId = -1L;
    private volatile boolean running;

    /**
     * Dedicated PostgreSQL connection for LISTEN/NOTIFY; non-null only when
     * {@link RelayStrategy#LISTEN_NOTIFY} is active and the connection is established.
     */
    private PgConnection listenConnection;

    /** Tracks the number of entries currently being processed across all handlers. */
    private final AtomicInteger inFlight = new AtomicInteger();

    // --- Constructor ---

    /**
     * Creates a new outbox relay verticle with no publish observers.
     *
     * @param config           relay configuration (polling interval, batch size, lease timeout, etc.)
     * @param outboxRepository the outbox repository for claiming and state updates
     * @param handlerMap       map from destination type to its handler implementation
     * @param capabilities     describes which destination types and targets this relay can handle
     * @param connectOptions   PostgreSQL connect options used for the LISTEN/NOTIFY connection
     * @param nodeId           stable identity of this relay instance (used as {@code claimed_by})
     */
    OutboxRelay(
            OutboxRelayConfig config,
            OutboxRepository outboxRepository,
            Map<DestinationType, OutboxDestinationHandler> handlerMap,
            RelayCapabilities capabilities,
            PgConnectOptions connectOptions,
            String nodeId) {
        this(config, outboxRepository, handlerMap, capabilities, connectOptions, nodeId, List.of());
    }

    /**
     * Creates a new outbox relay verticle that notifies the given publish observers.
     *
     * @param config           relay configuration (polling interval, batch size, lease timeout, etc.)
     * @param outboxRepository the outbox repository for claiming and state updates
     * @param handlerMap       map from destination type to its handler implementation
     * @param capabilities     describes which destination types and targets this relay can handle
     * @param connectOptions   PostgreSQL connect options used for the LISTEN/NOTIFY connection
     * @param nodeId           stable identity of this relay instance (used as {@code claimed_by})
     * @param observers        the publish observers to notify after every attempt; copied and sorted
     *                         by {@link OrderedExtension#comparator()} here, once
     */
    OutboxRelay(
            OutboxRelayConfig config,
            OutboxRepository outboxRepository,
            Map<DestinationType, OutboxDestinationHandler> handlerMap,
            RelayCapabilities capabilities,
            PgConnectOptions connectOptions,
            String nodeId,
            List<OutboxPublishObserver> observers) {
        List<OutboxPublishObserver> sorted = new ArrayList<>(observers);
        sorted.sort(OrderedExtension.comparator());
        this.observers = Collections.unmodifiableList(sorted);
        this.config = config;
        this.outboxRepository = outboxRepository;
        this.handlerMap = handlerMap;
        this.capabilities = capabilities;
        this.connectOptions = connectOptions;
        this.nodeId = nodeId;
    }

    // --- Verticle lifecycle ---

    /**
     * Starts the relay by scheduling the poll loop. When the configured strategy is
     * {@link RelayStrategy#LISTEN_NOTIFY}, also opens a dedicated PostgreSQL connection and issues
     * a {@code LISTEN} command. Stale-lease recovery and table cleanup are owned by the
     * cluster-singleton {@code OutboxMaintenanceService} and are not scheduled here.
     *
     * @param startPromise the startup promise to complete when the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise) {
        running = true;
        relayContext = context;
        currentPollDelay = config.pollingIntervalMs();

        schedulePoll();

        if (config.strategy() == RelayStrategy.LISTEN_NOTIFY) {
            openListenConnection()
                    .onSuccess(v -> {
                        log.info(
                                "OutboxRelay started (LISTEN_NOTIFY) nodeId={} batchSize={}",
                                nodeId,
                                config.batchSize());
                        startPromise.complete();
                    })
                    .onFailure(err -> {
                        // Fall back to polling-only if LISTEN setup fails; do not fail the verticle
                        log.warn(
                                "OutboxRelay: LISTEN/NOTIFY connection failed, falling back to polling. Reason: {}",
                                err.getMessage());
                        log.info(
                                "OutboxRelay started (POLLING fallback) nodeId={} batchSize={}",
                                nodeId,
                                config.batchSize());
                        startPromise.complete();
                    });
        } else {
            log.info("OutboxRelay started (POLLING) nodeId={} batchSize={}", nodeId, config.batchSize());
            startPromise.complete();
        }
    }

    /**
     * Stops the relay by cancelling all timers and closing the LISTEN connection if open.
     *
     * @param stopPromise the shutdown promise to complete when cleanup is done
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        running = false;
        cancelTimer(pollTimerId);

        if (listenConnection != null) {
            listenConnection.close().onComplete(ar -> {
                listenConnection = null;
                log.info("OutboxRelay stopped nodeId={}", nodeId);
                stopPromise.complete();
            });
        } else {
            log.info("OutboxRelay stopped nodeId={}", nodeId);
            stopPromise.complete();
        }
    }

    // --- Poll loop ---

    /**
     * Schedules the next poll cycle using a one-shot timer so that the polling interval is
     * measured from the end of the previous cycle.
     */
    private void schedulePoll() {
        if (!running) {
            return;
        }
        // raw timer: adaptive data-plane poll loop (next delay computed from prior batch result)
        pollTimerId = vertx.setTimer(currentPollDelay, id -> {
            if (running) {
                poll();
            }
        });
    }

    /**
     * Executes one poll cycle: checks available capacity, claims eligible entries from the
     * repository, dispatches each one, and reschedules.
     *
     * <p>The next poll is always scheduled, exactly once per cycle: after the claim has settled and
     * its entries have been dispatched, or at once when no claim is made. A claim that throws
     * synchronously, or returns {@code null} instead of a future, is a failed poll. A claim that
     * completes with a {@code null} list is an empty batch. An {@link Error} this method does not
     * handle still propagates, after the next poll has been scheduled.
     */
    private void poll() {
        Future<List<OutboxRecord>> claimed;
        try {
            int capacity = config.batchSize() - inFlight.get();
            if (capacity <= 0) {
                log.debug("OutboxRelay nodeId={}: all slots in use, skipping poll", nodeId);
                schedulePoll();
                return;
            }
            claimed = Objects.requireNonNull(
                    outboxRepository.claimBatch(capacity, nodeId, capabilities),
                    "OutboxRepository.claimBatch() returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            claimed = Future.failedFuture(e);
        } catch (Throwable t) {
            schedulePoll();
            throw t;
        }

        claimed.onComplete(ar -> {
            try {
                if (ar.succeeded()) {
                    dispatchClaimed(ar.result() != null ? ar.result() : List.of());
                } else {
                    log.warn(
                            "OutboxRelay nodeId={}: poll failed: {}",
                            nodeId,
                            ar.cause().getMessage(),
                            ar.cause());
                }
            } finally {
                schedulePoll();
            }
        });
    }

    /**
     * Adapts the poll delay to the size of a claimed batch and processes each of its entries. An
     * empty batch doubles the delay up to its cap; a non-empty one resets it.
     *
     * @param records the claimed entries; may be empty
     */
    private void dispatchClaimed(List<OutboxRecord> records) {
        if (records.isEmpty()) {
            currentPollDelay = Math.min(currentPollDelay * 2, Math.min(config.pollingIntervalMs() * 4, 60_000L));
            return;
        }
        currentPollDelay = config.pollingIntervalMs();
        log.debug("OutboxRelay nodeId={}: claimed {} entries", nodeId, records.size());
        for (OutboxRecord record : records) {
            inFlight.incrementAndGet();
            processRecord(record);
        }
    }

    // --- Entry processing ---

    /**
     * Delivers a single claimed outbox entry to its registered destination handler.
     *
     * <p>The envelope is built first, so every path below has one to hand to the publish observers.
     * The handler is selected by {@link DestinationType}. If no handler is registered for the
     * entry's destination type, the entry is returned to {@code PENDING} via
     * {@link OutboxPublishResult.Unresolvable}. Exceptions from the handler are treated as
     * retryable failures. So is an {@link AssertionError}, a {@link LinkageError} or a
     * {@link StackOverflowError} thrown synchronously by the handler, a handler that returns
     * {@code null} instead of a future, and a handler future that succeeds with {@code null}: each is
     * a failed attempt of that one entry, so the entry's in-flight slot is released and the caller
     * goes on with the rest of the claimed batch.
     *
     * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown while the
     * envelope is built does not leave this method either: it releases the slot and leaves the entry
     * claimed, to be returned to {@code PENDING} by stale-lease recovery; with no envelope there is
     * no attempt to report, so observers are not notified.
     *
     * <p>Any other {@link Error} — an {@link OutOfMemoryError}, or a {@link StackOverflowError} from
     * anywhere but the handler's publish call — does leave this method. The entry's slot is released
     * first unless the outcome handling already owns it, the entry stays claimed until stale-lease
     * recovery, and the caller schedules the next poll before the error propagates.
     *
     * @param record the claimed outbox entry to deliver
     */
    private void processRecord(OutboxRecord record) {
        // The slot belongs to this method until the outcome handling takes it over; handleResult
        // releases it itself, so it must not be released here as well.
        boolean[] slotHandedOver = {false};
        try {
            processRecord(record, slotHandedOver);
        } finally {
            if (!slotHandedOver[0]) {
                inFlight.decrementAndGet();
            }
        }
    }

    /**
     * Does the work of {@link #processRecord(OutboxRecord)}.
     *
     * @param record         the claimed outbox entry to deliver
     * @param slotHandedOver set to {@code true} just before the outcome handling takes over the
     *                       entry's in-flight slot; while it is {@code false} the caller releases
     *                       the slot
     */
    private void processRecord(OutboxRecord record, boolean[] slotHandedOver) {
        final OutboxEnvelope envelope;
        try {
            envelope = buildEnvelope(record);
        } catch (Exception | LinkageError | AssertionError e) {
            // Letting the throwable out of here would leave the batch loop early: the remaining
            // claimed entries would wait for lease recovery. The caller releases the slot.
            log.error(
                    "OutboxRelay: could not build the envelope for entryId={}; the entry stays claimed until"
                            + " stale-lease recovery: {}",
                    record.id(),
                    e.getMessage(),
                    e);
            return;
        }

        OutboxDestinationHandler handler = handlerMap.get(record.destinationType());
        if (handler == null) {
            log.warn(
                    "OutboxRelay: no handler registered for destinationType={} entryId={}",
                    record.destinationType(),
                    record.id());
            slotHandedOver[0] = true;
            handleResult(
                    record,
                    envelope,
                    OutboxPublishResult.unresolvable("No handler for: " + record.destinationType()),
                    Duration.ZERO);
            return;
        }

        long publishStartedNanos = System.nanoTime();
        Future<OutboxPublishResult> publishResult;
        try {
            publishResult = Objects.requireNonNull(
                    handler.publish(envelope), "OutboxDestinationHandler.publish() returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError | StackOverflowError e) {
            // A broken handler must cost one attempt of this entry only. Letting the throwable out of
            // here would leave the batch loop early: the remaining claimed entries would wait for
            // lease recovery.
            log.warn(
                    "OutboxRelay: synchronous exception from handler for entryId={}: {}",
                    record.id(),
                    e.getMessage(),
                    e);
            publishResult = Future.succeededFuture(
                    OutboxPublishResult.retryable("Synchronous adapter failure: " + e.getMessage(), e));
        }
        slotHandedOver[0] = true;
        publishResult
                .recover(err -> {
                    log.warn(
                            "OutboxRelay: unexpected exception from handler for entryId={}: {}",
                            record.id(),
                            err.getMessage(),
                            err);
                    return Future.succeededFuture(
                            OutboxPublishResult.retryable("Unexpected adapter failure: " + err.getMessage(), err));
                })
                .onSuccess(result -> handleResult(
                        record, envelope, result, Duration.ofNanos(System.nanoTime() - publishStartedNanos)));
    }

    /**
     * Builds the delivery {@link OutboxEnvelope} for a claimed record.
     *
     * <p>The envelope {@code headers} carry only application/transport headers from the stored
     * outbox row — no framework control keys ({@code x-message-id}, {@code eventType},
     * {@code aggregateType}, {@code aggregateId}) are injected. Relay-control values are instead
     * projected into {@link OutboxDeliveryMetadata#outbox()} as an {@link OutboxRelayControl}
     * built from the record's first-class columns. The persisted {@code delayedJob} delivery
     * section (if present) is carried through from {@link OutboxRecord#metadata()} unchanged.
     *
     * @param record the claimed outbox record
     * @return the envelope to pass to the destination handler
     */
    OutboxEnvelope buildEnvelope(OutboxRecord record) {
        OutboxRelayControl relayControl = new OutboxRelayControl(
                record.id(), record.carrierId(), record.eventType(), record.aggregateType(), record.aggregateId());
        OutboxDeliveryMetadata delivery = new OutboxDeliveryMetadata(
                java.util.Optional.of(relayControl),
                record.metadata().delivery().delayedJob());
        OutboxMetadata envelopeMetadata = new OutboxMetadata(record.metadata().context(), delivery);
        return new OutboxEnvelope(
                record.id(),
                record.aggregateType(),
                record.aggregateId(),
                record.eventType(),
                record.destination(),
                record.payload(),
                record.headers(),
                envelopeMetadata,
                record.scheduledAt(),
                record.attempt(),
                record.createdAt());
    }

    /**
     * Handles a delivery outcome for a record whose envelope the caller does not hold: builds the
     * envelope and delegates to
     * {@link #handleResult(OutboxRecord, OutboxEnvelope, OutboxPublishResult, Duration)} with a zero
     * elapsed time.
     *
     * @param record the outbox record that was processed
     * @param result the publish result from the handler
     */
    void handleResult(OutboxRecord record, OutboxPublishResult result) {
        handleResult(record, buildEnvelope(record), result, Duration.ZERO);
    }

    /**
     * Handles the delivery outcome from a destination handler: asks the repository to record the
     * entry's next state, releases the in-flight slot, and notifies the publish observers once the
     * repository call has settled.
     *
     * <p>The slot is released as soon as the repository call has been made, not when it settles, so
     * the poll loop never waits for it. Only the observer notification waits.
     *
     * <p>A throw from the backoff computation or from the repository call does not leave this
     * method: it is handled as a repository call that failed, so the slot is released and the
     * observers are told that the state change was not recorded. So is a repository method that
     * returns {@code null} instead of a future.
     *
     * @param record   the outbox record that was processed
     * @param envelope the envelope built for the record
     * @param result   the publish result from the handler; {@code null} is a retryable failure
     * @param elapsed  the time the publish call took, or zero when none was made
     */
    private void handleResult(
            OutboxRecord record, OutboxEnvelope envelope, @Nullable OutboxPublishResult result, Duration elapsed) {
        Transition decided = null;
        Instant computedNextAttemptAt = null;
        Future<Boolean> recorded;
        try {
            decided = classify(record, result != null ? result : nullResult(record));
            computedNextAttemptAt = nextAttemptAt(record, decided.disposition());
            recorded = Objects.requireNonNull(
                    recordTransition(record, decided, computedNextAttemptAt),
                    "OutboxRepository returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            recorded = Future.failedFuture(e);
        } finally {
            inFlight.decrementAndGet();
        }

        Transition transition = decided;
        Instant nextAttemptAt = computedNextAttemptAt;
        recorded.onComplete(ar -> {
            if (ar.failed()) {
                log.warn(
                        "OutboxRelay: failed to record the outcome of entryId={}{}: {}",
                        record.id(),
                        transition != null ? " as " + transition.disposition() : "",
                        ar.cause().getMessage(),
                        ar.cause());
            }
            if (transition != null) {
                boolean dispositionRecorded = ar.succeeded() && Boolean.TRUE.equals(ar.result());
                runOnRelayContext(() ->
                        notifyObservers(record, envelope, transition, dispositionRecorded, nextAttemptAt, elapsed));
            }
        });
    }

    /**
     * Runs an action on this verticle's own context: directly when the caller is already on it,
     * otherwise through {@link Context#runOnContext}. A repository future may complete on another
     * context or on a thread that has none; observers are promised the relay's context either way.
     * Before the verticle is started there is no context to hop to and the action runs directly.
     *
     * @param action the action to run
     */
    private void runOnRelayContext(Runnable action) {
        Context relay = relayContext;
        if (relay == null || relay == Vertx.currentContext()) {
            action.run();
        } else {
            relay.runOnContext(ignored -> action.run());
        }
    }

    /**
     * The relay's decision for one attempt: how the attempt ended and what happens to the entry.
     *
     * @param outcome     the classified handler result
     * @param disposition the state change to ask the repository for
     * @param lastError   the failure message to store with the entry, or {@code null}
     * @param errorType   the class name of the failure's cause, or {@code null}
     */
    private record Transition(
            OutboxPublishOutcome outcome,
            OutboxEntryDisposition disposition,
            @Nullable String lastError,
            @Nullable String errorType) {}

    /**
     * Builds the retryable failure that stands in for a handler future that succeeded with
     * {@code null}.
     *
     * @param record the outbox record that was processed
     * @return a retryable failure whose cause is a {@link NullPointerException}
     */
    private static OutboxPublishResult nullResult(OutboxRecord record) {
        NullPointerException cause =
                new NullPointerException("OutboxDestinationHandler.publish() completed with a null result");
        log.warn("OutboxRelay: handler for entryId={} completed with a null result", record.id());
        return OutboxPublishResult.retryable("Unexpected adapter failure: " + cause.getMessage(), cause);
    }

    /**
     * Decides what happens to an entry after an attempt: published on success, retried on a
     * retryable failure while attempts are left and dead-lettered once they are used up,
     * dead-lettered on a permanent failure, deferred when unresolvable.
     *
     * @param record the outbox record that was processed
     * @param result the publish result from the handler
     * @return the outcome of the attempt and the state change to record
     */
    private static Transition classify(OutboxRecord record, OutboxPublishResult result) {
        return switch (result) {
            case OutboxPublishResult.Success ignored -> {
                log.debug("OutboxRelay: published entryId={} destination={}", record.id(), record.destination());
                yield new Transition(OutboxPublishOutcome.SUCCESS, OutboxEntryDisposition.PUBLISHED, null, null);
            }
            case OutboxPublishResult.RetryableFailure failure -> {
                boolean exhausted = record.attempt() + 1 >= record.maxAttempts();
                if (exhausted) {
                    log.warn(
                            "OutboxRelay: entryId={} exhausted {} attempts, dead-lettering. Error: {}",
                            record.id(),
                            record.maxAttempts(),
                            failure.message());
                }
                yield new Transition(
                        OutboxPublishOutcome.RETRYABLE_FAILURE,
                        exhausted ? OutboxEntryDisposition.DEAD_LETTERED : OutboxEntryDisposition.RETRY_SCHEDULED,
                        failure.message(),
                        typeName(failure.cause()));
            }
            case OutboxPublishResult.PermanentFailure failure -> {
                log.warn(
                        "OutboxRelay: permanent failure for entryId={}, dead-lettering. Error: {}",
                        record.id(),
                        failure.message());
                yield new Transition(
                        OutboxPublishOutcome.PERMANENT_FAILURE,
                        OutboxEntryDisposition.DEAD_LETTERED,
                        failure.message(),
                        typeName(failure.cause()));
            }
            case OutboxPublishResult.Unresolvable unresolvable -> {
                log.warn(
                        "OutboxRelay: entryId={} is unresolvable, backing off {}s. Reason: {}",
                        record.id(),
                        UNRESOLVABLE_BACKOFF.toSeconds(),
                        unresolvable.message());
                yield new Transition(OutboxPublishOutcome.UNRESOLVABLE, OutboxEntryDisposition.DEFERRED, null, null);
            }
        };
    }

    /**
     * Computes the earliest time of the entry's next attempt: the backoff time for a scheduled
     * retry, now plus the unresolvable deferral for a deferred entry.
     *
     * @param record      the outbox record that was processed
     * @param disposition the state change being recorded
     * @return the next attempt time, or {@code null} when the entry will not be attempted again
     */
    @Nullable
    private Instant nextAttemptAt(OutboxRecord record, OutboxEntryDisposition disposition) {
        return switch (disposition) {
            case RETRY_SCHEDULED ->
                OutboxBackoff.computeNextAvailableAt(
                        record.attempt() + 1, config.backoffBaseDelayMs(), config.backoffMaxDelayMs());
            case DEFERRED -> Instant.now().plus(UNRESOLVABLE_BACKOFF);
            case PUBLISHED, DEAD_LETTERED -> null;
        };
    }

    /**
     * Asks the repository to record the decided state change.
     *
     * @param record        the outbox record that was processed
     * @param transition    the decided state change
     * @param nextAttemptAt the next attempt time of a scheduled retry; unused otherwise
     * @return the repository's future: {@code true} when the entry was updated, {@code false} when
     *     this relay no longer owned it
     */
    private Future<Boolean> recordTransition(
            OutboxRecord record, Transition transition, @Nullable Instant nextAttemptAt) {
        return switch (transition.disposition()) {
            case PUBLISHED -> outboxRepository.markPublished(record.id(), nodeId);
            case RETRY_SCHEDULED -> {
                int newAttempt = record.attempt() + 1;
                log.debug(
                        "OutboxRelay: retrying entryId={} attempt={}/{} availableAt={}",
                        record.id(),
                        newAttempt,
                        record.maxAttempts(),
                        nextAttemptAt);
                yield outboxRepository.markRetry(
                        record.id(), nodeId, newAttempt, nextAttemptAt, transition.lastError(), transition.errorType());
            }
            case DEAD_LETTERED ->
                outboxRepository.markDeadLetter(record.id(), nodeId, transition.lastError(), transition.errorType());
            case DEFERRED -> outboxRepository.markUnresolvable(record.id(), nodeId, UNRESOLVABLE_BACKOFF);
        };
    }

    // --- Publish observers ---

    /**
     * Notifies every registered {@link OutboxPublishObserver} of one completed attempt, in sorted
     * order. Called on the relay's context. An {@link Exception}, {@link LinkageError} or {@link AssertionError} from one observer
     * does not stop the remaining observers and never reaches the caller. An {@link Exception} or
     * {@link AssertionError} is logged at warn level each time. A {@link LinkageError} means the
     * observer cannot run at all and would repeat for every attempt, so it is logged at error level
     * the first time it is seen for an observer class, and not logged again for it. Any other
     * {@link Error} propagates.
     *
     * @param record              the outbox record that was processed
     * @param envelope            the envelope built for the record
     * @param transition          the outcome of the attempt and the state change asked for
     * @param dispositionRecorded whether the repository confirmed the state change
     * @param nextAttemptAt       the next attempt time, or {@code null}
     * @param elapsed             the time the publish call took, or zero when none was made
     */
    private void notifyObservers(
            OutboxRecord record,
            OutboxEnvelope envelope,
            Transition transition,
            boolean dispositionRecorded,
            @Nullable Instant nextAttemptAt,
            Duration elapsed) {
        if (observers.isEmpty()) {
            return;
        }
        final OutboxPublishCompletedEvent event;
        try {
            event = new OutboxPublishCompletedEvent(
                    String.valueOf(record.id()),
                    record.destinationType(),
                    envelope.destination(),
                    envelope.attempt(),
                    record.maxAttempts(),
                    transition.outcome(),
                    transition.disposition(),
                    dispositionRecorded,
                    nextAttemptAt,
                    transition.errorType(),
                    elapsed,
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn(
                    "OutboxRelay: could not build the publish event for entryId={}; observers are not notified: {}",
                    record.id(),
                    e.getMessage(),
                    e);
            return;
        }
        for (OutboxPublishObserver observer : observers) {
            try {
                observer.onPublishCompleted(event, envelope);
            } catch (LinkageError e) {
                if (reportedUnusable.add(observer.getClass())) {
                    log.error(
                            "OutboxRelay: publish observer {} is unusable and its notifications are being lost;"
                                    + " further failures of this observer are not logged",
                            observer.getClass().getName(),
                            e);
                }
            } catch (Exception | AssertionError e) {
                log.warn(
                        "OutboxRelay: publish observer {} threw an exception — swallowing: {}",
                        observer.getClass().getName(),
                        e.getMessage(),
                        e);
            }
        }
    }

    // --- LISTEN/NOTIFY ---

    /**
     * Opens a dedicated PostgreSQL connection and issues a {@code LISTEN} command on
     * {@link #NOTIFY_CHANNEL}. When a notification arrives, triggers an immediate poll.
     * If the connection is lost, the verticle continues in polling-only mode.
     *
     * @return a future that completes when the LISTEN command is issued
     */
    private Future<Void> openListenConnection() {
        return PgConnection.connect(vertx, connectOptions).compose(conn -> {
            conn.notificationHandler(notification -> {
                if (NOTIFY_CHANNEL.equals(notification.getChannel()) && running) {
                    log.trace("OutboxRelay: received NOTIFY on channel={}, triggering poll", NOTIFY_CHANNEL);
                    currentPollDelay = config.pollingIntervalMs();
                    cancelTimer(pollTimerId);
                    // raw timer: near-immediate wakeup triggered by PostgreSQL NOTIFY
                    pollTimerId = vertx.setTimer(1, id -> {
                        if (running) {
                            poll();
                        }
                    });
                }
            });
            conn.closeHandler(v -> {
                listenConnection = null;
                log.warn("OutboxRelay: LISTEN connection closed, falling back to polling-only nodeId={}", nodeId);
            });
            // Assign listenConnection only after LISTEN succeeds to avoid resource leak
            return conn.query("LISTEN " + NOTIFY_CHANNEL)
                    .execute()
                    .mapEmpty()
                    .map(v -> {
                        listenConnection = conn;
                        return (Void) null;
                    })
                    .recover(err -> {
                        conn.close();
                        return Future.failedFuture(err);
                    });
        });
    }

    // --- Helpers ---

    /**
     * Cancels a Vert.x timer by ID. No-ops when the timer ID is {@code -1L}.
     *
     * @param timerId the timer ID to cancel
     */
    private void cancelTimer(long timerId) {
        if (timerId != -1L) {
            vertx.cancelTimer(timerId);
        }
    }

    /**
     * Returns the simple class name of the cause's class, or {@code null} if {@code cause} is
     * {@code null}.
     *
     * @param cause the throwable, or {@code null}
     * @return the class name string, or {@code null}
     */
    private static String typeName(Throwable cause) {
        return cause != null ? cause.getClass().getName() : null;
    }

    /**
     * Builds a handler map from a set of {@link OutboxDestinationHandler} instances, keyed
     * by {@link DestinationType}.
     *
     * @param handlers the set of handlers to index
     * @return an immutable map from destination type to handler
     */
    static Map<DestinationType, OutboxDestinationHandler> buildHandlerMap(
            java.util.Set<OutboxDestinationHandler> handlers) {
        Map<DestinationType, OutboxDestinationHandler> map = new HashMap<>();
        for (OutboxDestinationHandler handler : handlers) {
            OutboxDestinationHandler previous = map.put(handler.destinationType(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate OutboxDestinationHandler for type " + handler.destinationType());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Derives the relay's per-type claim scopes from the deduped handler map.
     *
     * <p>Iterates each entry in the handler map, calls {@link OutboxDestinationHandler#claimScope()}
     * on the handler, and collects the results into a {@link RelayCapabilities} instance keyed by
     * the same {@link DestinationType} that the dispatch path uses. The resulting capabilities are
     * passed to {@link OutboxRepository#claimBatch} so the claim query admits only rows this relay
     * node is capable of delivering.
     *
     * <p>Fails fast with a {@link NullPointerException} if any handler returns {@code null} from
     * {@link OutboxDestinationHandler#claimScope()}, naming the offending destination type in the
     * message. A {@code null} scope is a programming error in the handler implementation; failing
     * at startup rather than silently over-claiming or under-claiming is the safe default.
     *
     * @param handlerMap the deduped map of destination types to handlers, as returned by
     *                   {@link #buildHandlerMap(java.util.Set)}
     * @return a {@link RelayCapabilities} whose {@code byType} map contains one entry per handler,
     *         keyed by the same {@link DestinationType} the dispatch path uses for lookup
     * @throws NullPointerException if any handler's {@link OutboxDestinationHandler#claimScope()}
     *                              returns {@code null}; the message names the offending type id
     */
    static RelayCapabilities deriveCapabilities(Map<DestinationType, OutboxDestinationHandler> handlerMap) {
        Map<DestinationType, ClaimScope> byType = new HashMap<>();
        for (Map.Entry<DestinationType, OutboxDestinationHandler> entry : handlerMap.entrySet()) {
            ClaimScope scope = entry.getValue().claimScope();
            Objects.requireNonNull(
                    scope,
                    "OutboxDestinationHandler.claimScope() returned null for destination type '"
                            + entry.getKey().id() + "'");
            byType.put(entry.getKey(), scope);
        }
        return new RelayCapabilities(byType);
    }
}
