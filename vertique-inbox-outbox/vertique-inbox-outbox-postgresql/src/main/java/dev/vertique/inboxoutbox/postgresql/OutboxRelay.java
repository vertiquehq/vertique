// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxBackoff;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 * claim cycles are skipped. Each entry occupies one slot from claim until its outcome is recorded.
 *
 * <p><b>Stale lease recovery and cleanup</b> are no longer owned by this verticle — they run as
 * cluster-singleton cron jobs in
 * {@code OutboxMaintenanceServiceImpl} (see
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

    // --- Runtime state ---

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
     * Creates a new outbox relay verticle.
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
     */
    private void poll() {
        int capacity = config.batchSize() - inFlight.get();
        if (capacity <= 0) {
            log.debug("OutboxRelay nodeId={}: all slots in use, skipping poll", nodeId);
            schedulePoll();
            return;
        }

        outboxRepository
                .claimBatch(capacity, nodeId, capabilities)
                .onSuccess(records -> {
                    if (records.isEmpty()) {
                        currentPollDelay =
                                Math.min(currentPollDelay * 2, Math.min(config.pollingIntervalMs() * 4, 60_000L));
                    } else {
                        currentPollDelay = config.pollingIntervalMs();
                        log.debug("OutboxRelay nodeId={}: claimed {} entries", nodeId, records.size());
                        for (OutboxRecord record : records) {
                            inFlight.incrementAndGet();
                            processRecord(record);
                        }
                    }
                    schedulePoll();
                })
                .onFailure(err -> {
                    log.warn("OutboxRelay nodeId={}: poll failed: {}", nodeId, err.getMessage(), err);
                    schedulePoll();
                });
    }

    // --- Entry processing ---

    /**
     * Delivers a single claimed outbox entry to its registered destination handler.
     *
     * <p>The handler is selected by {@link DestinationType}. If no handler is registered for the
     * entry's destination type, the entry is returned to {@code PENDING} via
     * {@link OutboxPublishResult.Unresolvable}. Exceptions from the handler are treated as
     * retryable failures per {@code FR-TM-032}.
     *
     * @param record the claimed outbox entry to deliver
     */
    private void processRecord(OutboxRecord record) {
        OutboxDestinationHandler handler = handlerMap.get(record.destinationType());
        if (handler == null) {
            log.warn(
                    "OutboxRelay: no handler registered for destinationType={} entryId={}",
                    record.destinationType(),
                    record.id());
            handleResult(record, OutboxPublishResult.unresolvable("No handler for: " + record.destinationType()));
            return;
        }

        OutboxEnvelope envelope = buildEnvelope(record);

        Future<OutboxPublishResult> publishResult;
        try {
            publishResult = handler.publish(envelope);
        } catch (Exception e) {
            log.warn(
                    "OutboxRelay: synchronous exception from handler for entryId={}: {}",
                    record.id(),
                    e.getMessage(),
                    e);
            publishResult = Future.succeededFuture(
                    OutboxPublishResult.retryable("Synchronous adapter failure: " + e.getMessage(), e));
        }
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
                .onSuccess(result -> handleResult(record, result));
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
     * Handles the delivery outcome from a destination handler by updating the outbox entry state
     * and releasing the in-flight slot.
     *
     * @param record the outbox record that was processed
     * @param result the publish result from the handler
     */
    void handleResult(OutboxRecord record, OutboxPublishResult result) {
        try {
            switch (result) {
                case OutboxPublishResult.Success ignored -> {
                    log.debug("OutboxRelay: published entryId={} destination={}", record.id(), record.destination());
                    outboxRepository
                            .markPublished(record.id(), nodeId)
                            .onFailure(err -> log.warn(
                                    "OutboxRelay: failed to mark entryId={} PUBLISHED: {}",
                                    record.id(),
                                    err.getMessage()));
                }
                case OutboxPublishResult.RetryableFailure failure -> {
                    int newAttempt = record.attempt() + 1;
                    if (newAttempt >= record.maxAttempts()) {
                        log.warn(
                                "OutboxRelay: entryId={} exhausted {} attempts, dead-lettering. Error: {}",
                                record.id(),
                                record.maxAttempts(),
                                failure.message());
                        deadLetter(record, failure.message(), typeName(failure.cause()));
                    } else {
                        Instant availableAt = OutboxBackoff.computeNextAvailableAt(
                                newAttempt, config.backoffBaseDelayMs(), config.backoffMaxDelayMs());
                        log.debug(
                                "OutboxRelay: retrying entryId={} attempt={}/{} availableAt={}",
                                record.id(),
                                newAttempt,
                                record.maxAttempts(),
                                availableAt);
                        outboxRepository
                                .markRetry(
                                        record.id(),
                                        nodeId,
                                        newAttempt,
                                        availableAt,
                                        failure.message(),
                                        typeName(failure.cause()))
                                .onFailure(err -> log.warn(
                                        "OutboxRelay: failed to mark entryId={} for retry: {}",
                                        record.id(),
                                        err.getMessage()));
                    }
                }
                case OutboxPublishResult.PermanentFailure failure -> {
                    log.warn(
                            "OutboxRelay: permanent failure for entryId={}, dead-lettering. Error: {}",
                            record.id(),
                            failure.message());
                    deadLetter(record, failure.message(), typeName(failure.cause()));
                }
                case OutboxPublishResult.Unresolvable unresolvable -> {
                    log.warn(
                            "OutboxRelay: entryId={} is unresolvable, backing off {}s. Reason: {}",
                            record.id(),
                            UNRESOLVABLE_BACKOFF.toSeconds(),
                            unresolvable.message());
                    outboxRepository
                            .markUnresolvable(record.id(), nodeId, UNRESOLVABLE_BACKOFF)
                            .onFailure(err -> log.warn(
                                    "OutboxRelay: failed to mark entryId={} as unresolvable: {}",
                                    record.id(),
                                    err.getMessage()));
                }
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Marks an outbox entry as dead-lettered.
     *
     * @param record    the entry to dead-letter
     * @param lastError human-readable error message
     * @param errorType exception class name, or {@code null} if not available
     */
    private void deadLetter(OutboxRecord record, String lastError, String errorType) {
        outboxRepository
                .markDeadLetter(record.id(), nodeId, lastError, errorType)
                .onFailure(err ->
                        log.warn("OutboxRelay: failed to dead-letter entryId={}: {}", record.id(), err.getMessage()));
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
