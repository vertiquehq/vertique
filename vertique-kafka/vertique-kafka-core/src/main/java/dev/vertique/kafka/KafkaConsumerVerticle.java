// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.async.Timeouts;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordIdentity;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x verticle that drives a single Kafka consumer binding.
 *
 * <p>At start, creates a {@link KafkaConsumer}{@code <String, byte[]>}, sets the record handler,
 * and subscribes to the configured topic. If the consumer is disabled, start completes immediately
 * without creating a Kafka connection.
 *
 * <p>A {@link CommitStrategy#MANUAL} consumer hands every record to a {@link KafkaSettlement}. Records of a
 * partition run concurrently, up to {@link ResolvedKafkaConsumerConfig#maxInFlight()} at once, and the consumer
 * commits only the offset after the longest prefix of records that have finished. The consumer is never paused as a
 * whole and nothing seeks: a partition stops fetching, and the client keeps polling, while it holds as many records as
 * its retention bound allows, while a record of an ordinary binding waits out a retry backoff and while the dispatch
 * gate is closed. A {@link CommitStrategy#AUTO} consumer is paused as a whole at {@code maxInFlight} dispatches and
 * resumes as they finish.
 *
 * <p>Error handling follows the configured {@link ErrorStrategy}, and applies to a {@code MANUAL} consumer only,
 * because {@code RETRY} and {@code DEAD_LETTER} need it:
 *
 * <ul>
 *   <li>{@link ErrorStrategy#SKIP} — logs and settles the record, so its offset is committed.
 *   <li>{@link ErrorStrategy#DEAD_LETTER} — publishes to the configured DLQ topic and settles the record once the
 *       publish is acknowledged. A publish that fails is made again after a backoff, and the record stays
 *       uncommitted until it is acknowledged.
 *   <li>{@link ErrorStrategy#RETRY} — keeps the record in memory and runs it through the whole pipeline again after
 *       the backoff. Retries up to {@link RetryConfig#maxRetries()} times; falls back to the exhausted
 *       strategy thereafter.
 * </ul>
 *
 * <p>For the {@link ConsumerEntry.Kind#HANDLER} kind, the record is dispatched directly to the
 * injected {@link KafkaRecordHandler} rather than the event bus.
 *
 * <p>Dispatch, deserialization, route resolution, error handling, and interceptor chaining are
 * delegated to focused collaborators: {@link KafkaRecordDispatcher}, {@link KafkaErrorHandler},
 * and {@link KafkaConsumerInterceptorChain}.
 *
 * <p>When a record's disposition is final (after all async operations have settled), every
 * interceptor's {@link KafkaConsumerInterceptor#onRecordCompleted} is called exactly once with the
 * final {@link KafkaTerminalOutcome} and a framework-owned {@link KafkaConsumerRecordView}. This
 * happens on every path a record can take: filtered, rejected by a filter that throws, unroutable,
 * undeserializable, dispatched, recovered or handed to the error strategy. The one limit: an
 * asynchronous interceptor or handler future that never completes, or a throw from inside an
 * asynchronous continuation, leaves the record without a completion. The view is built once per
 * record, before the filter, route resolution, deserialization and the interceptor chain, so nothing
 * downstream can replace it. Its value is the array the broker delivered, never a copy. A failing
 * observer is logged and swallowed and can never affect commit, retry, or dead-lettering. A consumer
 * with no interceptors builds no view.
 */
@Slf4j
public class KafkaConsumerVerticle extends AbstractVerticle {

    // --- Header constants ---

    private static final String HEADER_CORRELATION_ID = "x-correlation-id";

    // --- MDC constants ---

    private static final String MDC_CONSUMER = "kafka.consumer";
    private static final String MDC_TOPIC = "kafka.topic";
    private static final String MDC_PARTITION = "kafka.partition";
    private static final String MDC_OFFSET = "kafka.offset";
    private static final String MDC_CORRELATION_ID = "kafka.correlationId";

    /** Leave time for Kafka consumer and Vert.x shutdown after draining in-flight work. */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 10_000L;

    /** Bound Kafka client close while preserving time for the enclosing Vert.x shutdown. */
    private static final long KAFKA_CLOSE_TIMEOUT_MS = 10_000L;

    // --- Dependencies ---

    private final ConsumerEntry entry;
    private final List<KafkaConsumerInterceptor> interceptors;
    private final Map<String, String> kafkaProps;
    private final KafkaProducerFactory producerFactory;
    private final long shutdownDrainTimeoutMs;
    private final long kafkaCloseTimeoutMs;

    // --- Pre-built deserializer cache (ROUTER kind) ---

    /** Cached deserializers keyed by value type, built at construction time for ROUTER routes. */
    private final Map<Class<?>, KafkaDeserializer<?>> routeDeserializers;

    /** Serde registry used for type-agnostic Avro router routing in the dispatcher. */
    private final dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry;

    // --- Runtime state ---

    private KafkaConsumer<String, byte[]> consumer;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    /** Guards {@link #inFlightPaused}: dispatches of an {@code AUTO} consumer can finish on any thread. */
    private final Object pauseLock = new Object();

    /** Whether an {@code AUTO} consumer is paused as a whole at its in-flight limit. */
    private boolean inFlightPaused;

    /** Settles the records of a {@code MANUAL} consumer; {@code null} for an {@code AUTO} consumer. */
    private final KafkaSettlement settlement;

    /** The context the verticle runs on, once started: the one context every settlement call runs on. */
    private ContextInternal ownerContext;

    // --- Collaborators (initialised in start()) ---

    private KafkaErrorHandler errorHandler;
    private KafkaRecordDispatcher dispatcher;
    private KafkaConsumerInterceptorChain interceptorChain;

    private final ServiceRequestSender requestSender;
    private final ServiceTargetResolver targetResolver;
    private final EventBusClient eventBusClient;
    private final InboundExecutionContextScope inboundExecutionContextScope;
    private final DispatchEnvelopeBuilder envelopeBuilder;

    /**
     * Creates a new consumer verticle.
     *
     * @param entry           the consumer entry describing the binding, config, and dispatch target
     * @param interceptors    sorted list of interceptors to run around dispatch and to notify once
     *        per record when its disposition is final
     * @param producerFactory the shared Kafka producer factory used for DLQ publishing
     * @param requestSender   the service request sender for target-aware dispatch
     * @param targetResolver  the resolver for looking up service targets by stable id
     * @param eventBusClient  the low-level event bus client for fire-and-forget sends
     * @param inboundExecutionContextScope the substrate lifecycle helper that binds inbound
     *        durable metadata and runs registered {@code InboundContextInitializer}s before
     *        dispatch
     * @param envelopeBuilder the dispatch envelope builder for constructing outgoing envelopes
     * @param serdeRegistry   the serde registry used to build route deserializers and drive
     *        type-agnostic Avro router routing
     */
    public KafkaConsumerVerticle(
            ConsumerEntry entry,
            List<KafkaConsumerInterceptor> interceptors,
            KafkaProducerFactory producerFactory,
            ServiceRequestSender requestSender,
            ServiceTargetResolver targetResolver,
            EventBusClient eventBusClient,
            InboundExecutionContextScope inboundExecutionContextScope,
            DispatchEnvelopeBuilder envelopeBuilder,
            dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry) {
        this(
                entry,
                interceptors,
                producerFactory,
                requestSender,
                targetResolver,
                eventBusClient,
                inboundExecutionContextScope,
                envelopeBuilder,
                serdeRegistry,
                SHUTDOWN_DRAIN_TIMEOUT_MS,
                KAFKA_CLOSE_TIMEOUT_MS);
    }

    /** Package-private timeout seam keeps shutdown deadline tests fast and deterministic. */
    KafkaConsumerVerticle(
            ConsumerEntry entry,
            List<KafkaConsumerInterceptor> interceptors,
            KafkaProducerFactory producerFactory,
            ServiceRequestSender requestSender,
            ServiceTargetResolver targetResolver,
            EventBusClient eventBusClient,
            InboundExecutionContextScope inboundExecutionContextScope,
            DispatchEnvelopeBuilder envelopeBuilder,
            dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry,
            long shutdownDrainTimeoutMs,
            long kafkaCloseTimeoutMs) {
        this.entry = entry;
        this.interceptors = List.copyOf(interceptors);
        this.producerFactory = producerFactory;
        this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
        this.kafkaCloseTimeoutMs = kafkaCloseTimeoutMs;
        this.requestSender = requestSender;
        this.targetResolver = targetResolver;
        this.eventBusClient = eventBusClient;
        this.inboundExecutionContextScope = inboundExecutionContextScope;
        this.envelopeBuilder = envelopeBuilder;
        this.serdeRegistry = serdeRegistry;
        this.kafkaProps = buildKafkaProperties(entry.config());
        this.routeDeserializers = buildRouteDeserializers(entry, serdeRegistry);
        this.settlement = entry.config().commitStrategy() == CommitStrategy.MANUAL
                ? new KafkaSettlement(settlementSettings(entry), new SettlementPort())
                : null;
    }

    // --- Lifecycle ---

    /**
     * Starts the consumer verticle. If the entry is disabled, completes immediately. Otherwise,
     * creates the Kafka consumer, initialises collaborators, sets the record handler, and subscribes
     * to the topic.
     *
     * @param startPromise the promise to complete when the verticle has started
     */
    @Override
    public void start(Promise<Void> startPromise) {
        ResolvedKafkaConsumerConfig cfg = entry.config();
        if (!cfg.enabled()) {
            log.info("Kafka consumer '{}' is disabled, skipping", entry.name());
            startPromise.complete();
            return;
        }

        ownerContext = (ContextInternal) context;
        consumer = KafkaConsumer.create(vertx, kafkaProps, String.class, byte[].class);
        consumer.handler(this::processRecord);
        if (settlement != null) {
            // Both run on the consumer's context, in the order the client saw them, after the records it returned
            // before them. An assignment is a new ledger, and a revocation ends the ledger and commits its prefix.
            consumer.partitionsAssignedHandler(partitions -> settlement.assigned(partitions));
            consumer.partitionsRevokedHandler(partitions -> settlement.revoked(partitions));
        }
        consumer.exceptionHandler(cause -> log.error("[{}] Kafka consumer exception", entry.name(), cause));

        // Collaborators require vertx, which is only available after injection by AbstractVerticle.
        // If building the dispatcher (which builds the router routing deserializer) throws, close the
        // route serdes already built in the constructor and the consumer before failing start, so a
        // doomed deployment leaves no orphaned registry clients (stop() is not called on failed start).
        try {
            errorHandler = new KafkaErrorHandler(entry, producerFactory);
            dispatcher = new KafkaRecordDispatcher(
                    entry,
                    routeDeserializers,
                    serdeRegistry,
                    requestSender,
                    targetResolver,
                    eventBusClient,
                    inboundExecutionContextScope,
                    envelopeBuilder);
            interceptorChain = new KafkaConsumerInterceptorChain(entry.name(), interceptors);
        } catch (RuntimeException e) {
            closeRouteSerdes();
            consumer.close().onComplete(ar -> {
                if (ar.failed()) {
                    e.addSuppressed(ar.cause());
                }
                startPromise.fail(e);
            });
            return;
        }

        consumer.subscribe(cfg.topic()).onComplete(ar -> {
            if (ar.succeeded()) {
                log.info("[{}] Subscribed to topic '{}' with group '{}'", entry.name(), cfg.topic(), cfg.groupId());
                startPromise.complete();
            } else {
                // A failed start does not guarantee stop() runs, so release the deployment-owned
                // resources here (route/routing serdes + the Kafka consumer) before failing, mirroring
                // the dispatcher-build failure path and preserving close failures as suppressed.
                Throwable cause = ar.cause();
                log.error("[{}] Failed to subscribe to topic '{}'", entry.name(), cfg.topic(), cause);
                closeRouteSerdes();
                consumer.close().onComplete(cr -> {
                    if (cr.failed()) {
                        cause.addSuppressed(cr.cause());
                    }
                    startPromise.fail(cause);
                });
            }
        });
    }

    /**
     * Stops the consumer verticle. A {@code MANUAL} consumer stops starting records and fetching, waits up to
     * {@value #SHUTDOWN_DRAIN_TIMEOUT_MS} milliseconds for the records that run, the dead-letter publishes and the
     * commits that are outstanding, and commits the settled prefix of each partition; records that did not finish stay
     * uncommitted for the next run. An {@code AUTO} consumer pauses its partitions and waits for its in-flight
     * dispatches. Either then closes the consumer for at most {@value #KAFKA_CLOSE_TIMEOUT_MS} milliseconds.
     *
     * @param stopPromise the promise to complete when the verticle has stopped
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (consumer == null) {
            stopPromise.complete();
            return;
        }

        if (settlement != null) {
            settlement.beginStop();
        } else {
            // Pause all assigned partitions so no new records arrive
            consumer.assignment()
                    .onSuccess(partitions -> consumer.pause(partitions))
                    .onFailure(
                            cause -> log.warn("[{}] Failed to pause partitions during shutdown", entry.name(), cause));
        }

        // Reserve time within Vert.x's shutdown deadline for closing the Kafka consumer itself.
        Timeouts.awaitCondition(
                        vertx,
                        () -> settlement != null ? settlement.drained() : inFlight.get() <= 0,
                        100L,
                        shutdownDrainTimeoutMs,
                        "Kafka consumer '" + entry.name() + "' in-flight drain")
                .onComplete(drainResult -> {
                    int pending = settlement != null ? settlement.running() : inFlight.get();
                    boolean drainTimedOut = drainResult.failed();
                    if (drainTimedOut && pending > 0) {
                        log.warn(
                                "[{}] In-flight records did not drain within {} ms; closing consumer with {} record(s) pending",
                                entry.name(),
                                shutdownDrainTimeoutMs,
                                pending);
                    }
                    if (settlement == null) {
                        closeAfterDrain(stopPromise, drainTimedOut, pending);
                        return;
                    }
                    // The final commit is the settled prefix of each partition, never the client's position.
                    settlement.commitSettled();
                    Timeouts.awaitCondition(
                                    vertx,
                                    settlement::committed,
                                    50L,
                                    kafkaCloseTimeoutMs,
                                    "Kafka consumer '" + entry.name() + "' final commit")
                            .onFailure(cause -> log.warn(
                                    "[{}] The final commit did not finish; the records after it are read again",
                                    entry.name(),
                                    cause))
                            .onComplete(commitResult -> {
                                settlement.finishStop();
                                closeAfterDrain(stopPromise, drainTimedOut, pending);
                            });
                });
    }

    private void closeAfterDrain(Promise<Void> stopPromise, boolean drainTimedOut, int pending) {
        closeRouteSerdes();
        Timeouts.withTimeout(consumer.close(), kafkaCloseTimeoutMs, "Kafka consumer '" + entry.name() + "' close")
                .onSuccess(v -> log.info("[{}] Kafka consumer closed", entry.name()))
                .onFailure(cause -> log.warn("[{}] Error closing Kafka consumer", entry.name(), cause))
                .onComplete(closeResult -> {
                    if (drainTimedOut && pending > 0) {
                        TimeoutException timeout = new TimeoutException("Kafka consumer '" + entry.name()
                                + "' stopped with " + pending + " in-flight record(s) after " + shutdownDrainTimeoutMs
                                + " ms");
                        if (closeResult.failed()) {
                            timeout.addSuppressed(closeResult.cause());
                        }
                        stopPromise.fail(timeout);
                    } else if (closeResult.failed()) {
                        stopPromise.fail(closeResult.cause());
                    } else {
                        stopPromise.complete();
                    }
                });
    }

    // --- Record processing ---

    /**
     * Main entry point for each incoming Kafka record. A {@code MANUAL} consumer registers the record with its
     * {@link KafkaSettlement}, which runs and settles it. An {@code AUTO} consumer enforces backpressure and runs the
     * record at once.
     *
     * @param record the incoming Kafka consumer record
     */
    private void processRecord(KafkaConsumerRecord<String, byte[]> record) {
        if (settlement != null) {
            settlement.register(record);
            return;
        }
        incrementInFlight();
        runStages(record, 0).onComplete(ar -> {
            decrementInFlight();
            KafkaSettlement.Result result =
                    ar.succeeded() ? ar.result() : KafkaSettlement.Result.failed(ar.cause(), null);
            if (result.failure() == null) {
                notifyCompleted(result.view(), result.outcome());
            } else {
                KafkaErrorHandler.logSkipped(
                        entry.name(), record.topic(), record.partition(), record.offset(), result.failure());
                notifyCompleted(result.view(), KafkaTerminalOutcome.SKIP);
            }
        });
    }

    /**
     * Runs one record through the whole pipeline: the filter, route resolution, deserialization, the interceptors and
     * the dispatch. The future carries how the run ended and never fails.
     *
     * @param record the incoming Kafka consumer record
     * @param retryCount the retries that have started for this record, for the dispatch context and the view
     * @return the result of the run
     */
    private Future<KafkaSettlement.Result> runStages(KafkaConsumerRecord<String, byte[]> record, int retryCount) {
        // One faithful extraction per run. This one immutable instance is what the record view,
        // the filter, the router, the deserializers, the dispatch context, the record context, the
        // handler's message and the error handler are given. The text projection is computed once
        // here and reused for the framework's own text reads: the correlation id, header routing and
        // durable context decoding.
        KafkaRecordHeaders headers = extractHeaders(record);
        Map<String, String> textHeaders = headers.asMap();
        byte[] rawBytes = record.value();
        String correlationId = sanitizeForMdc(textHeaders.getOrDefault(
                HEADER_CORRELATION_ID, UUID.randomUUID().toString()));
        // The record view is fixed here, before the filter, route resolution, deserialization and any
        // interceptor, and is what completion observers receive on every path. A dispatch context
        // handed back by an interceptor never replaces it. Without interceptors nothing can observe
        // it, so none is built.
        KafkaConsumerRecordView view =
                interceptors.isEmpty() ? null : buildRecordView(record, rawBytes, headers, retryCount);

        // Pre-deserialization filter. Only the filter call is guarded: a filter that throws is handled
        // like a deserialization failure, so the record still completes.
        boolean accepted;
        try {
            accepted = entry.filter() == null || entry.filter().accept(record.key(), headers);
        } catch (Exception e) {
            return Future.succeededFuture(KafkaSettlement.Result.failed(e, view));
        }
        if (!accepted) {
            log.debug(
                    "[{}] Record filtered: topic={} partition={} offset={}",
                    entry.name(),
                    record.topic(),
                    record.partition(),
                    record.offset());
            return Future.succeededFuture(KafkaSettlement.Result.settled(KafkaTerminalOutcome.SKIP, view));
        }

        // Deserialize and dispatch
        Promise<KafkaSettlement.Result> done = Promise.promise();
        try {
            dispatchRecord(record, rawBytes, headers, textHeaders, correlationId, retryCount, view, done);
        } catch (Exception e) {
            done.tryComplete(KafkaSettlement.Result.failed(e, view));
        }
        return done.future();
    }

    /**
     * Deserializes the record value and runs the full dispatch pipeline, including interceptors.
     *
     * @param record the raw consumer record
     * @param rawBytes the raw value bytes
     * @param headers the record's headers as received
     * @param textHeaders the text projection of {@code headers}, computed once per record
     * @param correlationId the correlation ID for tracing
     * @param retryCount the retries that have started for this record
     * @param view the record's framework-owned view, handed to completion observers; {@code null}
     *     when the consumer has no interceptors
     * @param done completed with how the run ended
     */
    private void dispatchRecord(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            KafkaRecordHeaders headers,
            Map<String, String> textHeaders,
            String correlationId,
            int retryCount,
            @Nullable KafkaConsumerRecordView view,
            Promise<KafkaSettlement.Result> done) {

        // For router kind, find the matching route before deserialization
        KafkaRecordDispatcher.RouteResult routeResult = null;
        if (entry.kind() == ConsumerEntry.Kind.ROUTER) {
            routeResult = dispatcher.resolveRoute(headers, textHeaders, rawBytes, record.topic());
            if (routeResult == null) {
                log.debug(
                        "[{}] No matching route for record: topic={} offset={}",
                        entry.name(),
                        record.topic(),
                        record.offset());
                done.tryComplete(KafkaSettlement.Result.settled(KafkaTerminalOutcome.SKIP, view));
                return;
            }
        }

        final KafkaRecordDispatcher.RouteResult resolvedRouteResult = routeResult;

        // Deserialize
        Object deserialized;
        try {
            deserialized = dispatcher.deserializeRecord(record, rawBytes, headers, resolvedRouteResult);
        } catch (Exception e) {
            Runnable onIgnored = entry.ignoreUndecodable();
            if (onIgnored != null && !(e instanceof DeserializationException de && de.isTransient())) {
                // An ignoring binding acknowledges the undecodable record: it is reported skipped and
                // the callback runs; it does not go down the error strategy.
                // A transient failure, such as an unreachable schema registry, is not the record's
                // fault and does take the error strategy.
                runIgnoreCallback(onIgnored);
                done.tryComplete(KafkaSettlement.Result.settled(KafkaTerminalOutcome.SKIP, view));
                return;
            }
            done.tryComplete(KafkaSettlement.Result.failed(e, view));
            return;
        }

        // Build initial dispatch context (raw — before interceptors).
        // A tombstone record carries a null value on the wire; guard against NPE in downstream
        // capture code by using PayloadSources.absent() rather than buffered(null, …).
        KafkaDispatchContext<Object> dispatchCtx = new KafkaDispatchContext<>(
                entry.name(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                deserialized,
                rawBytes == null ? PayloadSources.absent() : PayloadSources.buffered(rawBytes, null),
                headers,
                record.timestamp(),
                retryCount,
                false,
                Map.of());

        // Run sync observers, then before-dispatch async interceptors
        interceptorChain.runOnRecordObservers(dispatchCtx);
        interceptorChain.runBeforeInterceptors(dispatchCtx).onComplete(interceptorResult -> {
            if (interceptorResult.failed()) {
                done.tryComplete(KafkaSettlement.Result.failed(interceptorResult.cause(), view));
                return;
            }

            KafkaDispatchContext<Object> ctx = interceptorResult.result();
            if (ctx.filtered()) {
                log.debug(
                        "[{}] Record filtered by interceptor: topic={} offset={}",
                        entry.name(),
                        record.topic(),
                        record.offset());
                interceptorChain
                        .runAfterInterceptors(ctx)
                        .onComplete(
                                v -> done.tryComplete(KafkaSettlement.Result.settled(KafkaTerminalOutcome.SKIP, view)));
                return;
            }

            // Build KafkaRecordContext for dispatch context propagation
            KafkaRecordContext recordContext = new KafkaRecordContext(
                    entry.name(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.timestamp(),
                    correlationId,
                    headers);

            // Dispatch based on kind
            Future<Void> dispatchFuture =
                    switch (entry.kind()) {
                        case BINDING ->
                            dispatcher.dispatchToEventBus(
                                    entry.targetAddress(),
                                    entry.stableTargetId(),
                                    entry.targetOneWay(),
                                    ctx.value(),
                                    recordContext,
                                    textHeaders,
                                    correlationId);
                        case ROUTER -> {
                            ConsumerEntry.RouteEntry route = resolvedRouteResult.route();
                            yield dispatcher.dispatchToEventBus(
                                    route.targetAddress(),
                                    route.stableTargetId(),
                                    route.targetOneWay(),
                                    ctx.value(),
                                    recordContext,
                                    textHeaders,
                                    correlationId);
                        }
                        case HANDLER -> dispatcher.dispatchToHandler(ctx.value(), record, headers, textHeaders);
                    };

            dispatchFuture.onComplete(dispatchResult -> {
                if (dispatchResult.succeeded()) {
                    interceptorChain.runOnSuccessObservers(ctx);
                    interceptorChain
                            .runAfterInterceptors(ctx)
                            .onComplete(v -> done.tryComplete(
                                    KafkaSettlement.Result.settled(KafkaTerminalOutcome.SUCCESS, view)));
                } else {
                    Throwable cause = dispatchResult.cause();
                    interceptorChain.runOnErrorObservers(ctx, cause);
                    interceptorChain
                            .runRecoverError(ctx, cause)
                            // Error was recovered by an interceptor — the record is settled
                            .onSuccess(v -> done.tryComplete(
                                    KafkaSettlement.Result.settled(KafkaTerminalOutcome.RECOVERED, view)))
                            // Not recovered — the binding's error strategy decides
                            .onFailure(e -> done.tryComplete(KafkaSettlement.Result.failed(e, view)));
                }
            });
        });
    }

    /**
     * Runs the callback of an ignored undecodable record. A callback that throws is logged by class
     * and does not change the record's outcome.
     *
     * @param onIgnored the binding's ignore callback
     */
    private void runIgnoreCallback(Runnable onIgnored) {
        try {
            onIgnored.run();
        } catch (RuntimeException e) {
            log.warn(
                    "[{}] Ignore callback failed: {}",
                    entry.name(),
                    e.getClass().getName());
        }
    }

    // --- Settlement (MANUAL) ---

    /**
     * Resolves the settings of the settlement from the entry: the permits, the strategies, the retention bounds and the
     * dispatch gate.
     */
    private static KafkaSettlement.Settings settlementSettings(ConsumerEntry entry) {
        ResolvedKafkaConsumerConfig cfg = entry.config();
        return new KafkaSettlement.Settings(
                entry.name(),
                cfg.maxInFlight(),
                cfg.errorStrategy(),
                cfg.retryConfig(),
                entry.forceStrategies(),
                cfg.retentionLimit(),
                entry.dispatchGate());
    }

    /** Runs the work on the consumer's owner context: inline when already there, queued otherwise. */
    private void onOwner(Runnable task) {
        ContextInternal owner = ownerContext;
        if (owner == null) {
            task.run();
            return;
        }
        Context current = Vertx.currentContext();
        if (current instanceof ContextInternal currentInternal && currentInternal.unwrap() == owner) {
            task.run();
        } else {
            owner.runOnContext(v -> task.run());
        }
    }

    /** What the settlement needs from this verticle's consumer, context and timers. */
    private final class SettlementPort implements KafkaSettlement.Port {

        @Override
        public Future<KafkaSettlement.Result> run(KafkaConsumerRecord<String, byte[]> record, int attempt) {
            ContextInternal owner = ownerContext;
            if (owner == null) {
                return runStages(record, attempt);
            }
            // Each run starts on a context of its own: the inbound scope binds a record's values on a duplicated
            // context, and a retry or a record that waited for the gate does not start from a record's callback.
            Promise<KafkaSettlement.Result> promise = Promise.promise();
            owner.duplicate().runOnContext(v -> {
                try {
                    runStages(record, attempt).onComplete(promise);
                } catch (Throwable thrown) {
                    promise.tryFail(thrown);
                }
            });
            return promise.future();
        }

        @Override
        public Future<Void> publishDlq(KafkaConsumerRecord<String, byte[]> record, Throwable cause) {
            return errorHandler.publishToDlq(record, record.value(), extractHeaders(record), cause);
        }

        @Override
        public Future<Void> commit(io.vertx.kafka.client.common.TopicPartition partition, long offset) {
            return consumer.commit(Map.of(partition, new io.vertx.kafka.client.consumer.OffsetAndMetadata(offset, "")))
                    .mapEmpty();
        }

        @Override
        public Future<Void> pause(io.vertx.kafka.client.common.TopicPartition partition) {
            return consumer.pause(partition);
        }

        @Override
        public Future<Void> resume(io.vertx.kafka.client.common.TopicPartition partition) {
            return consumer.resume(partition);
        }

        @Override
        public KafkaSettlement.Timer schedule(long delayMs, Runnable task) {
            long id = vertx.setTimer(Math.max(1L, delayMs), timer -> task.run());
            return () -> vertx.cancelTimer(id);
        }

        @Override
        public void execute(Runnable task) {
            onOwner(task);
        }

        @Override
        public void completed(KafkaConsumerRecordView view, KafkaTerminalOutcome outcome) {
            notifyCompleted(view, outcome);
        }
    }

    // --- Backpressure (AUTO) ---

    /**
     * Increments the in-flight counter and pauses the consumer if the threshold is reached.
     */
    void incrementInFlight() {
        int current = inFlight.incrementAndGet();
        if (current >= entry.config().maxInFlight()) {
            synchronized (pauseLock) {
                if (!inFlightPaused) {
                    inFlightPaused = true;
                    consumer.pause();
                    log.debug(
                            "[{}] Paused consumer: inFlight={} maxInFlight={}",
                            entry.name(),
                            current,
                            entry.config().maxInFlight());
                }
            }
        }
    }

    /**
     * Decrements the in-flight counter and resumes the consumer once the count has dropped below the
     * max-in-flight threshold.
     */
    void decrementInFlight() {
        int current = inFlight.decrementAndGet();
        if (current < entry.config().maxInFlight()) {
            synchronized (pauseLock) {
                if (inFlightPaused) {
                    inFlightPaused = false;
                    consumer.resume();
                    log.debug("[{}] Released the in-flight pause: inFlight={}", entry.name(), current);
                }
            }
        }
    }

    // --- Record completion ---

    /**
     * Tells every interceptor that a record has reached its final disposition. The interceptor chain
     * runs the observers in order and isolates their failures, so a misbehaving observer can never
     * affect commit, retry, or dead-lettering.
     *
     * @param view    the record's framework-owned view, built before the filter and any interceptor
     *                ran; {@code null} when the consumer has no interceptors
     * @param outcome the final disposition of this record
     */
    private void notifyCompleted(@Nullable KafkaConsumerRecordView view, KafkaTerminalOutcome outcome) {
        if (view == null) {
            return;
        }
        interceptorChain.runOnRecordCompleted(new KafkaConsumerCompletedEvent(view.identity(), outcome), view);
    }

    /**
     * Builds the framework-owned view of a record as the consumer received it. The headers are the
     * immutable extracted collection; the value array is not copied.
     *
     * @param record   the incoming consumer record
     * @param rawBytes the raw value bytes (may be null for tombstones)
     * @param headers  the headers extracted from the record
     * @param retryCount the retries that have started for the record
     * @return the view; never null
     */
    private KafkaConsumerRecordView buildRecordView(
            KafkaConsumerRecord<String, byte[]> record, byte[] rawBytes, KafkaRecordHeaders headers, int retryCount) {
        KafkaConsumerRecordIdentity identity = new KafkaConsumerRecordIdentity(
                entry.name(), record.topic(), record.partition(), record.offset(), record.timestamp(), retryCount);
        return new DefaultKafkaConsumerRecordView(identity, record.key(), headers, rawBytes);
    }

    // --- Utilities ---

    /**
     * Sanitizes a string value for safe insertion into MDC by replacing control characters
     * ({@code \r}, {@code \n}, {@code \t}) with underscores, preventing log injection attacks.
     *
     * @param value the raw value, or {@code null}
     * @return the sanitized value, or {@code null} if the input was {@code null}
     */
    private static String sanitizeForMdc(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * Extracts all Kafka record headers as they are on the wire: every header in order, with repeated
     * keys, {@code null} values and the exact value bytes. The result is immutable and is the one
     * header collection the whole pipeline is given for the record.
     *
     * <p>This runs before the guarded part of record processing, so it must not throw. A header with
     * a {@code null} key is skipped: Kafka cannot deliver one, so only a test double can produce it.
     *
     * @param record the consumer record to extract headers from
     * @return the headers; {@link KafkaRecordHeaders#empty()} when the record has none
     */
    private static KafkaRecordHeaders extractHeaders(KafkaConsumerRecord<String, byte[]> record) {
        List<KafkaHeader> wireHeaders = record.headers();
        if (wireHeaders == null || wireHeaders.isEmpty()) {
            return KafkaRecordHeaders.empty();
        }
        List<KafkaRecordHeader> entries = new ArrayList<>(wireHeaders.size());
        for (KafkaHeader header : wireHeaders) {
            if (header.key() == null) {
                continue;
            }
            // The header copies the buffer: that is the only copy made of a header value on receipt.
            entries.add(new KafkaRecordHeader(header.key(), header.value()));
        }
        return new KafkaRecordHeaders(entries);
    }

    /**
     * Builds the Kafka consumer properties map from the resolved config, adding required deserializer
     * properties for {@code String} key and {@code byte[]} value.
     *
     * @param cfg the resolved consumer config
     * @return a merged property map ready for {@link KafkaConsumer#create}
     */
    private static Map<String, String> buildKafkaProperties(ResolvedKafkaConsumerConfig cfg) {
        Map<String, String> props = new HashMap<>(cfg.kafkaProperties());
        props.put("group.id", cfg.groupId());
        // Always consume raw bytes — deserialization is handled in the verticle
        props.putIfAbsent("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.putIfAbsent("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        // For MANUAL commit strategy, disable auto-commit
        if (cfg.commitStrategy() == CommitStrategy.MANUAL) {
            props.put("enable.auto.commit", "false");
        }
        return props;
    }

    /**
     * Closes the per-instance serdes this verticle built (the ROUTER route deserializers and the
     * dispatcher's routing deserializer) so registry-backed clients are released on undeploy. The
     * shared {@code entry.deserializer()} (BINDING/HANDLER) is owned by the {@link ConsumerEntry} and
     * reused across deployment instances (and across redeploys), so closing it here would be a
     * use-after-close on the next deploy; it lives for the application lifetime instead.
     */
    private void closeRouteSerdes() {
        for (KafkaDeserializer<?> deserializer : routeDeserializers.values()) {
            safeClose(deserializer);
        }
        if (dispatcher != null) {
            try {
                dispatcher.close();
            } catch (RuntimeException e) {
                log.warn("[{}] Error closing routing deserializer", entry.name(), e);
            }
        }
    }

    private void safeClose(KafkaDeserializer<?> deserializer) {
        try {
            deserializer.close();
        } catch (RuntimeException e) {
            log.warn("[{}] Error closing route deserializer", entry.name(), e);
        }
    }

    /**
     * Pre-builds deserializers for all unique value types in ROUTER routes via the serde registry
     * (using the router's single resolved value format), avoiding per-record instantiation. Used for
     * header/default-matched routes; Avro property matches reuse the type-agnostically deserialized
     * record instead.
     *
     * @param entry the consumer entry to build deserializers for
     * @param serdeRegistry the serde registry used to build format-aware deserializers
     * @return an unmodifiable map of value type to deserializer
     */
    private static Map<Class<?>, KafkaDeserializer<?>> buildRouteDeserializers(
            ConsumerEntry entry, dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry) {
        if (entry.kind() != ConsumerEntry.Kind.ROUTER) {
            return Map.of();
        }
        Map<Class<?>, KafkaDeserializer<?>> map = new HashMap<>();
        try {
            for (ConsumerEntry.RouteEntry route : entry.routes()) {
                if (route.valueType() != null && route.valueType() != Void.class) {
                    map.computeIfAbsent(
                            route.valueType(),
                            vt -> serdeRegistry.deserializer(
                                    entry.valueFormat(), vt, entry.config().serdeConfig()));
                }
            }
        } catch (RuntimeException e) {
            // A later route serde failed to build — close those already built so a doomed verticle
            // construction does not orphan registry-backed clients (no owner reaches stop()).
            for (KafkaDeserializer<?> built : map.values()) {
                try {
                    built.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
        return Map.copyOf(map);
    }
}
