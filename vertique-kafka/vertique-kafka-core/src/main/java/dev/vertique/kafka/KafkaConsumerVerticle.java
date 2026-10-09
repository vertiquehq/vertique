// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundExecutionContextScope;
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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x verticle that drives a single Kafka consumer binding.
 *
 * <p>At start, creates a {@link KafkaConsumer}{@code <String, byte[]>}, sets the record handler,
 * and subscribes to the configured topic. If the consumer is disabled, start completes immediately
 * without creating a Kafka connection.
 *
 * <p>Backpressure is enforced via an {@link AtomicInteger} tracking in-flight dispatches. When
 * {@link ResolvedKafkaConsumerConfig#maxInFlight()} is reached, the consumer is paused. It is resumed once
 * the in-flight count drops below the threshold.
 *
 * <p>Error handling follows the configured {@link ErrorStrategy}:
 *
 * <ul>
 *   <li>{@link ErrorStrategy#SKIP} — logs and commits the offset.
 *   <li>{@link ErrorStrategy#DEAD_LETTER} — publishes to the configured DLQ topic, then commits.
 *   <li>{@link ErrorStrategy#RETRY} — pauses the consumer, seeks back to the failed record's
 *       offset, waits for the backoff period, then resumes. The record is re-polled from the seeked
 *       offset. Retries up to {@link RetryConfig#maxRetries()} times; falls back to the exhausted
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

    // --- Dependencies ---

    private final ConsumerEntry entry;
    private final List<KafkaConsumerInterceptor> interceptors;
    private final Map<String, String> kafkaProps;
    private final KafkaProducerFactory producerFactory;

    // --- Pre-built deserializer cache (ROUTER kind) ---

    /** Cached deserializers keyed by value type, built at construction time for ROUTER routes. */
    private final Map<Class<?>, KafkaDeserializer<?>> routeDeserializers;

    /** Serde registry used for type-agnostic Avro router routing in the dispatcher. */
    private final dev.vertique.kafka.serialization.KafkaSerdeRegistry serdeRegistry;

    // --- Runtime state ---

    private KafkaConsumer<String, byte[]> consumer;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicBoolean paused = new AtomicBoolean(false);

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
        this.entry = entry;
        this.interceptors = List.copyOf(interceptors);
        this.producerFactory = producerFactory;
        this.requestSender = requestSender;
        this.targetResolver = targetResolver;
        this.eventBusClient = eventBusClient;
        this.inboundExecutionContextScope = inboundExecutionContextScope;
        this.envelopeBuilder = envelopeBuilder;
        this.serdeRegistry = serdeRegistry;
        this.kafkaProps = buildKafkaProperties(entry.config());
        this.routeDeserializers = buildRouteDeserializers(entry, serdeRegistry);
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

        consumer = KafkaConsumer.create(vertx, kafkaProps, String.class, byte[].class);
        consumer.handler(this::processRecord);
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
     * Stops the consumer verticle. Pauses partition assignment to stop new records, waits up to 30
     * seconds for in-flight dispatches to drain, then closes the consumer.
     *
     * @param stopPromise the promise to complete when the verticle has stopped
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (consumer == null) {
            stopPromise.complete();
            return;
        }

        // Pause all assigned partitions so no new records arrive
        consumer.assignment()
                .onSuccess(partitions -> consumer.pause(partitions))
                .onFailure(cause -> log.warn("[{}] Failed to pause partitions during shutdown", entry.name(), cause));

        // Poll until in-flight drains or the timeout elapses (max 30s)
        long drainTimeoutMs = 30_000L;
        long startTime = System.currentTimeMillis();
        vertx.setPeriodic(100L, timerId -> {
            if (inFlight.get() <= 0 || System.currentTimeMillis() - startTime > drainTimeoutMs) {
                vertx.cancelTimer(timerId);
                if (errorHandler != null) {
                    errorHandler.retryCounts.clear();
                }
                closeRouteSerdes();
                consumer.close()
                        .onSuccess(v -> log.info("[{}] Kafka consumer closed", entry.name()))
                        .onFailure(cause -> log.warn("[{}] Error closing Kafka consumer", entry.name(), cause))
                        .onComplete(ar -> stopPromise.complete());
            }
        });
    }

    // --- Record processing ---

    /**
     * Main entry point for each incoming Kafka record. Enforces backpressure, runs the interceptor
     * pipeline, deserializes, and dispatches.
     *
     * @param record the incoming Kafka consumer record
     */
    private void processRecord(KafkaConsumerRecord<String, byte[]> record) {
        incrementInFlight();

        // One faithful extraction per record. The text map handed to the filter, router, deserializers,
        // dispatch context and error handler is derived from it, and stays a mutable copy because
        // filters and deserializers have always been given one.
        KafkaRecordHeaders recordHeaders = extractHeaders(record);
        Map<String, String> headers = new HashMap<>(recordHeaders.asMap());
        byte[] rawBytes = record.value();
        String correlationId = sanitizeForMdc(
                headers.getOrDefault(HEADER_CORRELATION_ID, UUID.randomUUID().toString()));
        // The record view is fixed here, before the filter, route resolution, deserialization and any
        // interceptor, and is what completion observers receive on every path. A dispatch context
        // handed back by an interceptor never replaces it. Without interceptors nothing can observe
        // it, so none is built.
        KafkaConsumerRecordView view = interceptors.isEmpty() ? null : buildRecordView(record, rawBytes, recordHeaders);

        // Pre-deserialization filter. Only the filter call is guarded: a filter that throws is handled
        // like a deserialization failure, so the record still completes and its slot is released.
        boolean accepted;
        try {
            accepted = entry.filter() == null || entry.filter().accept(record.key(), headers);
        } catch (Exception e) {
            decrementInFlight();
            errorHandler
                    .handleError(record, rawBytes, headers, e, consumerControl())
                    .onSuccess(outcome -> notifyCompleted(view, outcome))
                    .onFailure(err -> {
                        log.warn("[{}] Unexpected failure from handleError future", entry.name(), err);
                        notifyCompleted(view, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
                    });
            return;
        }
        if (!accepted) {
            log.debug(
                    "[{}] Record filtered: topic={} partition={} offset={}",
                    entry.name(),
                    record.topic(),
                    record.partition(),
                    record.offset());
            decrementInFlight();
            commitIfManual(record);
            notifyCompleted(view, KafkaTerminalOutcome.SKIP);
            return;
        }

        // Deserialize and dispatch
        try {
            dispatchRecord(record, rawBytes, headers, correlationId, view);
        } catch (Exception e) {
            decrementInFlight();
            errorHandler
                    .handleError(record, rawBytes, headers, e, consumerControl())
                    .onSuccess(outcome -> notifyCompleted(view, outcome))
                    .onFailure(err -> {
                        log.warn("[{}] Unexpected failure from handleError future", entry.name(), err);
                        // The error handler's own future failed — the record's disposition is
                        // unknown, but the record must still complete exactly once.
                        notifyCompleted(view, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
                    });
        }
    }

    /**
     * Deserializes the record value and runs the full dispatch pipeline, including interceptors.
     *
     * @param record the raw consumer record
     * @param rawBytes the raw value bytes
     * @param headers extracted header map
     * @param correlationId the correlation ID for tracing
     * @param view the record's framework-owned view, handed to completion observers; {@code null}
     *     when the consumer has no interceptors
     */
    private void dispatchRecord(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            Map<String, String> headers,
            String correlationId,
            @Nullable KafkaConsumerRecordView view) {

        // For router kind, find the matching route before deserialization
        KafkaRecordDispatcher.RouteResult routeResult = null;
        if (entry.kind() == ConsumerEntry.Kind.ROUTER) {
            routeResult = dispatcher.resolveRoute(headers, rawBytes, record.topic());
            if (routeResult == null) {
                log.debug(
                        "[{}] No matching route for record: topic={} offset={}",
                        entry.name(),
                        record.topic(),
                        record.offset());
                decrementInFlight();
                commitIfManual(record);
                notifyCompleted(view, KafkaTerminalOutcome.SKIP);
                return;
            }
        }

        final KafkaRecordDispatcher.RouteResult resolvedRouteResult = routeResult;

        // Deserialize
        Object deserialized;
        try {
            deserialized = dispatcher.deserializeRecord(record, rawBytes, headers, resolvedRouteResult);
        } catch (Exception e) {
            decrementInFlight();
            errorHandler
                    .handleError(record, rawBytes, headers, e, consumerControl())
                    .onSuccess(outcome -> notifyCompleted(view, outcome))
                    .onFailure(err -> {
                        log.warn("[{}] Unexpected failure from handleError future", entry.name(), err);
                        notifyCompleted(view, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
                    });
            return;
        }

        // Build initial dispatch context (raw — before interceptors).
        // A tombstone record carries a null value on the wire; guard against NPE in downstream
        // capture code by using PayloadSources.absent() rather than buffered(null, …).
        String retryKey = record.topic() + ":" + record.partition() + ":" + record.offset();
        int retryCount = errorHandler.retryCounts.getOrDefault(retryKey, 0);
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
                decrementInFlight();
                errorHandler
                        .handleError(record, rawBytes, headers, interceptorResult.cause(), consumerControl())
                        .onSuccess(outcome -> notifyCompleted(view, outcome))
                        .onFailure(err -> {
                            log.warn("[{}] Unexpected failure from handleError future", entry.name(), err);
                            notifyCompleted(view, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
                        });
                return;
            }

            KafkaDispatchContext<Object> ctx = interceptorResult.result();
            if (ctx.filtered()) {
                log.debug(
                        "[{}] Record filtered by interceptor: topic={} offset={}",
                        entry.name(),
                        record.topic(),
                        record.offset());
                decrementInFlight();
                interceptorChain.runAfterInterceptors(ctx).onComplete(v -> {
                    commitIfManual(record);
                    notifyCompleted(view, KafkaTerminalOutcome.SKIP);
                });
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
                                    headers,
                                    correlationId);
                        case ROUTER -> {
                            ConsumerEntry.RouteEntry route = resolvedRouteResult.route();
                            yield dispatcher.dispatchToEventBus(
                                    route.targetAddress(),
                                    route.stableTargetId(),
                                    route.targetOneWay(),
                                    ctx.value(),
                                    recordContext,
                                    headers,
                                    correlationId);
                        }
                        case HANDLER -> dispatcher.dispatchToHandler(ctx.value(), record, headers);
                    };

            dispatchFuture.onComplete(dispatchResult -> {
                decrementInFlight();
                if (dispatchResult.succeeded()) {
                    // Remove any retry state accumulated for this record on clean success
                    errorHandler.retryCounts.remove(retryKey);
                    interceptorChain.runOnSuccessObservers(ctx);
                    interceptorChain.runAfterInterceptors(ctx).onComplete(v -> {
                        commitIfManual(record);
                        notifyCompleted(view, KafkaTerminalOutcome.SUCCESS);
                    });
                } else {
                    Throwable cause = dispatchResult.cause();
                    interceptorChain.runOnErrorObservers(ctx, cause);
                    interceptorChain
                            .runRecoverError(ctx, cause)
                            .onSuccess(v -> {
                                // Error was recovered by an interceptor — commit and move on
                                commitIfManual(record);
                                notifyCompleted(view, KafkaTerminalOutcome.RECOVERED);
                            })
                            .onFailure(e -> {
                                // Not recovered — proceed with normal error strategy; complete the
                                // record from the terminal-outcome future so observers run after all
                                // async work (DLQ publish, seek, scheduleResume) has settled.
                                errorHandler
                                        .handleError(record, rawBytes, headers, e, consumerControl())
                                        .onSuccess(outcome -> notifyCompleted(view, outcome))
                                        .onFailure(err -> {
                                            log.warn(
                                                    "[{}] Unexpected failure from handleError future",
                                                    entry.name(),
                                                    err);
                                            notifyCompleted(view, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
                                        });
                            });
                }
            });
        });
    }

    // --- ConsumerControl ---

    /**
     * Builds a {@link KafkaErrorHandler.ConsumerControl} bound to this verticle's consumer instance.
     * Called on each error path so the control always references the live consumer.
     *
     * @return a consumer control delegating to the current consumer and verticle state
     */
    private KafkaErrorHandler.ConsumerControl consumerControl() {
        return new KafkaErrorHandler.ConsumerControl() {
            @Override
            public void pause() {
                if (paused.compareAndSet(false, true)) {
                    consumer.pause();
                }
            }

            @Override
            public void scheduleResume(long delayMs) {
                if (delayMs <= 0) {
                    if (paused.compareAndSet(true, false)) {
                        consumer.resume();
                    }
                } else {
                    vertx.setTimer(delayMs, id -> {
                        if (paused.compareAndSet(true, false)) {
                            consumer.resume();
                        }
                    });
                }
            }

            @Override
            public Future<Void> seekToOffset(String topic, int partition, long offset) {
                io.vertx.kafka.client.common.TopicPartition tp =
                        new io.vertx.kafka.client.common.TopicPartition(topic, partition);
                return consumer.seek(tp, offset);
            }

            @Override
            public void commitIfManual(KafkaConsumerRecord<String, byte[]> record) {
                KafkaConsumerVerticle.this.commitIfManual(record);
            }
        };
    }

    // --- Commit ---

    /**
     * Commits the offset for the given record if the commit strategy is {@link CommitStrategy#MANUAL}.
     *
     * @param record the record whose offset should be committed when in manual mode
     */
    private void commitIfManual(KafkaConsumerRecord<String, byte[]> record) {
        if (entry.config().commitStrategy() == CommitStrategy.MANUAL) {
            commitOffset(record);
        }
    }

    /**
     * Commits the offset for the specific partition of the given record.
     *
     * <p>Uses per-partition commit ({@code offset + 1}) to avoid accidentally committing offsets for
     * concurrently-dispatched records on other partitions.
     *
     * @param record the record whose offset should be committed
     */
    private void commitOffset(KafkaConsumerRecord<String, byte[]> record) {
        io.vertx.kafka.client.common.TopicPartition tp =
                new io.vertx.kafka.client.common.TopicPartition(record.topic(), record.partition());
        io.vertx.kafka.client.consumer.OffsetAndMetadata om =
                new io.vertx.kafka.client.consumer.OffsetAndMetadata(record.offset() + 1, "");
        consumer.commit(Map.of(tp, om))
                .onFailure(cause -> log.warn(
                        "[{}] Failed to commit offset for partition={} offset={}",
                        entry.name(),
                        record.partition(),
                        record.offset(),
                        cause));
    }

    // --- Backpressure ---

    /**
     * Increments the in-flight counter and pauses the consumer if the threshold is reached.
     */
    private void incrementInFlight() {
        int current = inFlight.incrementAndGet();
        if (current >= entry.config().maxInFlight() && paused.compareAndSet(false, true)) {
            consumer.pause();
            log.debug(
                    "[{}] Paused consumer: inFlight={} maxInFlight={}",
                    entry.name(),
                    current,
                    entry.config().maxInFlight());
        }
    }

    /**
     * Decrements the in-flight counter and resumes the consumer if it was paused and the count has
     * dropped below the max-in-flight threshold.
     */
    private void decrementInFlight() {
        int current = inFlight.decrementAndGet();
        if (current < entry.config().maxInFlight() && paused.compareAndSet(true, false)) {
            consumer.resume();
            log.debug("[{}] Resumed consumer: inFlight={}", entry.name(), current);
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
     * @return the view; never null
     */
    private KafkaConsumerRecordView buildRecordView(
            KafkaConsumerRecord<String, byte[]> record, byte[] rawBytes, KafkaRecordHeaders headers) {
        String retryKey = record.topic() + ":" + record.partition() + ":" + record.offset();
        int retryCount = errorHandler.retryCounts.getOrDefault(retryKey, 0);
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
     * keys, {@code null} values and the exact value bytes. The text map the rest of the pipeline uses
     * is {@link KafkaRecordHeaders#asMap()} of the result.
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
            Buffer value = header.value();
            entries.add(new KafkaRecordHeader(header.key(), value == null ? null : value.getBytes()));
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
