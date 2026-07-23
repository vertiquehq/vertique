// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.async.Futures;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.logging.MDCContexts;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches deserialized Kafka records to event bus services or direct handlers. Also handles
 * route resolution for {@link ConsumerEntry.Kind#ROUTER}-kind consumers and record deserialization.
 *
 * <p>Dispatch to the event bus goes through {@link ServiceRequestSender} when a stable target id
 * is available (enabling supervisor check and error enrichment), or falls back to address-based
 * dispatch via {@link ServiceRequestSender#send(String, DispatchEnvelope, long)} / {@link EventBusClient#send}
 * for entries without a stable target id.
 *
 * <p>All format-specific behavior (including JSON property routing) is encapsulated behind the
 * {@link KafkaSerdeRegistry} SPI — the dispatcher never touches Jackson or Avro types directly.
 *
 * <p>An instance is created once per {@link KafkaConsumerVerticle} and reused for every record.
 */
@Slf4j
final class KafkaRecordDispatcher {

    // --- MDC keys (restored after envelope refactor) ---

    private static final String MDC_CONSUMER = "kafka.consumer";
    private static final String MDC_TOPIC = "kafka.topic";
    private static final String MDC_PARTITION = "kafka.partition";
    private static final String MDC_OFFSET = "kafka.offset";
    private static final String MDC_CORRELATION_ID = "kafka.correlationId";

    private final ConsumerEntry entry;
    private final Map<Class<?>, KafkaDeserializer<?>> routeDeserializers;
    private final KafkaSerdeRegistry serdeRegistry;

    /**
     * Type-agnostic routing deserializer built once for any router that has at least one property
     * route. Built from the entry's merged serde config so endpoint registry/auth settings apply
     * during route selection (FR-AVRO-007/009; NFR-AVRO-002 build-once). {@code null} for
     * non-routers and for property-less (header/default-only) routers.
     */
    private final KafkaDeserializer<Object> routingDeserializer;

    /** Whether this router has at least one {@code matchProperty} route (computed once at build). */
    private final boolean hasPropertyRoute;

    private final ServiceRequestSender requestSender;
    private final ServiceTargetResolver targetResolver;
    private final EventBusClient eventBusClient;
    private final InboundExecutionContextScope inboundExecutionContextScope;
    private final DispatchEnvelopeBuilder envelopeBuilder;

    /** Cache of resolved service targets keyed by stable target id to avoid per-record resolution.
     *  Plain HashMap is safe — each dispatcher runs on a single Vert.x event loop. */
    private final Map<String, ResolvedServiceTarget> resolvedTargets = new java.util.HashMap<>();

    /**
     * Creates a new dispatcher for the given consumer entry.
     *
     * @param entry              the consumer entry describing routes, target, and config
     * @param routeDeserializers pre-built deserializers keyed by route value type (for ROUTER kind)
     * @param serdeRegistry      the serde registry used for type-agnostic router routing via
     *                           {@code routingDeserializer}, {@code matchValue}, and
     *                           {@code convertRouted}
     * @param requestSender      the service request sender for target-aware request/reply and one-way dispatch
     * @param targetResolver     the resolver for looking up {@link ResolvedServiceTarget} by stable target id
     * @param eventBusClient     the low-level event bus client for address-only fire-and-forget sends
     * @param inboundExecutionContextScope the substrate lifecycle helper that binds inbound durable
     *                           metadata and runs registered {@code InboundContextInitializer}s
     *                           (including the correlation seeder) before dispatch
     * @param envelopeBuilder    the dispatch envelope builder for constructing outgoing envelopes
     */
    KafkaRecordDispatcher(
            ConsumerEntry entry,
            Map<Class<?>, KafkaDeserializer<?>> routeDeserializers,
            KafkaSerdeRegistry serdeRegistry,
            ServiceRequestSender requestSender,
            ServiceTargetResolver targetResolver,
            EventBusClient eventBusClient,
            InboundExecutionContextScope inboundExecutionContextScope,
            DispatchEnvelopeBuilder envelopeBuilder) {
        this.entry = entry;
        this.routeDeserializers = routeDeserializers;
        this.serdeRegistry = serdeRegistry;
        // True only for a ROUTER carrying a matchProperty route. Non-routers have no routes, so the
        // kind guard makes the invariant explicit rather than relying on an empty route list.
        this.hasPropertyRoute = entry.kind() == ConsumerEntry.Kind.ROUTER
                && entry.routes().stream().anyMatch(KafkaRecordDispatcher::isPropertyRoute);
        // Build the type-agnostic routing deserializer once, only for a router that has at least
        // one property route (threading the entry's merged serde config so endpoint registry/auth
        // settings apply during selection — FR-AVRO-007/009; NFR-AVRO-002 build-once). A
        // header/default-only router never needs it, and the provider's routingDeserializer is
        // optional per format.
        this.routingDeserializer = hasPropertyRoute
                ? serdeRegistry.routingDeserializer(
                        entry.valueFormat(), entry.config().serdeConfig())
                : null;
        this.requestSender = requestSender;
        this.targetResolver = targetResolver;
        this.eventBusClient = eventBusClient;
        this.inboundExecutionContextScope = inboundExecutionContextScope;
        this.envelopeBuilder = envelopeBuilder;
    }

    /**
     * Closes per-instance serdes owned by this dispatcher (the router routing deserializer). Called
     * by the owning verticle on undeploy so the registry client it holds is released.
     */
    void close() {
        if (routingDeserializer != null) {
            routingDeserializer.close();
        }
    }

    // --- Deserialization ---

    /**
     * Deserializes the raw record bytes using the appropriate deserializer. For router kind, the
     * routing value carried in {@link RouteResult} is converted to the route's target type via the
     * SPI's {@link KafkaSerdeRegistry#convertRouted}, reusing the already-decoded value to avoid a
     * second parse (FR-AVRO-007). For binding/handler kind, uses the entry-level deserializer.
     *
     * @param record the Kafka consumer record (for topic and header context)
     * @param rawBytes the raw value bytes
     * @param headers the extracted headers
     * @param routeResult the resolved route result including optional pre-routed value (may be
     *     {@code null} for non-ROUTER kinds)
     * @return the deserialized value
     * @throws DeserializationException if deserialization fails
     */
    Object deserializeRecord(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            Map<String, String> headers,
            RouteResult routeResult)
            throws DeserializationException {

        if (rawBytes == null) {
            return null;
        }

        if (entry.kind() == ConsumerEntry.Kind.ROUTER && routeResult != null) {
            ConsumerEntry.RouteEntry route = routeResult.route();
            // No-parameter handler: skip deserialization entirely
            if (route.valueType() == Void.class) {
                return null;
            }
            try {
                if (routeResult.routingValue() != null) {
                    // Property match: delegate to the SPI to convert/reuse the pre-decoded routing
                    // value (avoids a second wire-byte parse for both JSON and Avro). The provider's
                    // default implementation reuses when assignable and throws DeserializationException
                    // otherwise — preventing a bad discriminator/schema dispatching the wrong type
                    // (FR-AVRO-007).
                    return serdeRegistry.convertRouted(
                            entry.valueFormat(),
                            routeResult.routingValue(),
                            route.valueType(),
                            entry.config().serdeConfig());
                }
                // Header/default match: use the cached (format-aware) deserializer for the route type
                KafkaDeserializer<?> routeDeserializer = routeDeserializers.get(route.valueType());
                if (routeDeserializer != null) {
                    return routeDeserializer.deserialize(rawBytes, record.topic(), headers);
                }
                throw new DeserializationException(
                        "No deserializer available for route value type "
                                + route.valueType().getName() + " on topic " + record.topic(),
                        null);
            } catch (DeserializationException de) {
                throw de;
            } catch (Exception e) {
                throw new DeserializationException(
                        "Failed to deserialize " + route.valueType().getSimpleName() + " from topic " + record.topic()
                                + ": " + e.getMessage(),
                        e);
            }
        }

        KafkaDeserializer<?> deserializer = entry.deserializer();
        if (deserializer == null) {
            if (entry.kind() == ConsumerEntry.Kind.BINDING) {
                throw new DeserializationException("No deserializer configured for consumer " + entry.name(), null);
            }
            // HANDLER kind: pass raw bytes to handler for custom processing
            return rawBytes;
        }
        return deserializer.deserialize(rawBytes, record.topic(), headers);
    }

    // --- Route resolution ---

    /**
     * Resolves the matching route for a ROUTER-kind consumer, checking header matching first, then
     * property-based matching (which requires format-aware deserialization via the SPI). Returns the
     * default route if no specific route matches. Returns {@code null} if no route matches and no
     * default is configured.
     *
     * <p>Header and default routes select without any deserialization (format-agnostic). A
     * property-match round deserializes the payload exactly once through the SPI: the
     * {@link KafkaSerdeRegistry#routingDeserializer} yields the format's intermediate routing value
     * (a parsed object tree, a decoded record, etc.), and
     * {@link KafkaSerdeRegistry#matchValue} reads the discriminator field from it — the dispatcher
     * never touches format-specific types directly (FR-AVRO-007). The matched routing value is
     * carried in the {@link RouteResult} so {@link #deserializeRecord} can convert it to the route's
     * target type without a second parse.
     *
     * @param headers the extracted Kafka record headers
     * @param rawBytes the raw record value bytes (used for property-based matching)
     * @param topic the Kafka topic (needed by schema-registry routing deserialize)
     * @return the resolved route with optional routing value, or {@code null}
     * @throws DeserializationException if the raw bytes cannot be deserialized during
     *     property-based route matching
     */
    RouteResult resolveRoute(Map<String, String> headers, byte[] rawBytes, String topic) {
        ConsumerEntry.RouteEntry defaultRoute = null;
        for (ConsumerEntry.RouteEntry route : entry.routes()) {
            if (route.defaultHandler()) {
                defaultRoute = route;
                continue;
            }
            if (!route.matchHeader().isBlank()) {
                String headerValue = headers.get(route.matchHeader());
                if (route.matchValue().equals(headerValue)) {
                    return new RouteResult(route, null);
                }
            }
        }

        if (rawBytes != null) {
            RouteResult propertyMatch = resolvePropertyRoute(rawBytes, topic, headers);
            if (propertyMatch != null) {
                return propertyMatch;
            }
        }

        return defaultRoute != null ? new RouteResult(defaultRoute, null) : null;
    }

    /** A route that selects on a discriminator field (not a header or default route). */
    private static boolean isPropertyRoute(ConsumerEntry.RouteEntry route) {
        return !route.defaultHandler() && !route.matchProperty().isBlank();
    }

    /**
     * Property routing: deserialize the record once type-agnostically via the registry, then read
     * each discriminator via the registry — keeping format-specific types out of core. Serves every
     * format (each yields its own intermediate routing value). Only invoked when at least one
     * property route exists; the matched routing value is carried in the result for reuse as the
     * payload.
     *
     * @param rawBytes the raw record value bytes
     * @param topic the Kafka topic
     * @param headers the record headers
     * @return the matched route (carrying the routing value for payload reuse), or {@code null} if
     *     none matched
     * @throws DeserializationException if the record cannot be deserialized for routing
     */
    private RouteResult resolvePropertyRoute(byte[] rawBytes, String topic, Map<String, String> headers) {
        if (!hasPropertyRoute || routingDeserializer == null) {
            return null;
        }
        Object routingValue;
        try {
            routingValue = routingDeserializer.deserialize(rawBytes, topic, headers);
        } catch (DeserializationException de) {
            throw de;
        } catch (RuntimeException e) {
            throw new DeserializationException(
                    "Failed to deserialize " + entry.valueFormat() + " record for property-based routing on topic "
                            + topic,
                    e);
        }
        if (routingValue == null) {
            return null;
        }
        // Resolve the provider once per record rather than re-resolving it on every property route
        // (the registry's matchValue would do a map lookup per iteration otherwise).
        KafkaSerdeProvider provider = serdeRegistry.provider(entry.valueFormat());
        for (ConsumerEntry.RouteEntry route : entry.routes()) {
            if (!isPropertyRoute(route)) {
                continue;
            }
            String propertyValue = provider.matchValue(routingValue, route.matchProperty());
            if (route.matchValue().equals(propertyValue)) {
                return new RouteResult(route, routingValue);
            }
        }
        return null;
    }

    // --- Dispatch ---

    /**
     * Dispatches the deserialized value to an event bus address, using request-reply or send
     * semantics depending on the commit strategy and one-way flag.
     *
     * <p>Before constructing the dispatch envelope, inbound Kafka record headers are bound into
     * the current {@link dev.vertique.core.context.ContextHolder} scope via
     * {@link InboundExecutionContextScope#installDurable(Map, String)} (FR-CTX-174). The substrate
     * helper also runs registered {@code InboundContextInitializer}s — notably the correlation
     * seeder, so records arriving without an encoded CorrelationContext still get a freshly seeded
     * one bound (FR-COR-125). The scope is closed in the terminal handler of the dispatch future
     * regardless of outcome.
     *
     * <p>The dispatch envelope is built through {@link DispatchEnvelopeBuilder#build(Object, Map, String)}
     * so that any registered {@link dev.vertique.core.context.ServiceDispatchContextEncoder}s
     * automatically capture context values from the holder into the outgoing envelope (FR-CTX-015).
     *
     * <p>When a {@code stableTargetId} is provided, dispatch goes through
     * {@link ServiceRequestSender} for supervisor check and error enrichment:
     * <ul>
     *   <li>Request/reply with target: {@link ServiceRequestSender#send(ResolvedServiceTarget, DispatchEnvelope, long)}</li>
     *   <li>Request/reply without target: {@link ServiceRequestSender#send(String, DispatchEnvelope, long)}</li>
     *   <li>Fire-and-forget with target: {@link ServiceRequestSender#sendOneWay(ResolvedServiceTarget, DispatchEnvelope)}</li>
     *   <li>Fire-and-forget without target: {@link EventBusClient#send(String, DispatchEnvelope)}</li>
     * </ul>
     *
     * @param targetAddress  the event bus address to dispatch to
     * @param stableTargetId the durable dot-delimited stable target id, or {@code null} if
     *                       the operation has no {@link dev.vertique.services.ServiceOperation} annotation
     * @param oneWay         whether to use fire-and-forget semantics
     * @param value          the deserialized payload value
     * @param recordContext  the Kafka record context for dispatch context propagation
     * @param headers        the Kafka headers used both for MDC correlation and durable context binding
     * @param correlationId  the correlation ID
     * @return a future that succeeds or fails based on the dispatch outcome
     */
    Future<Void> dispatchToEventBus(
            String targetAddress,
            String stableTargetId,
            boolean oneWay,
            Object value,
            KafkaRecordContext recordContext,
            Map<String, String> headers,
            String correlationId) {

        if (targetAddress == null) {
            // No dispatch target (e.g., router default handler with no @DispatchTo)
            return Future.succeededFuture();
        }

        // Bind durable context from Kafka headers, build the envelope, resolve targets, and
        // dispatch inside one defensive try block so any synchronous failure — including a
        // duplicated-context invariant violation thrown from bindFrom — flows through a failed
        // future. KafkaConsumerVerticle invokes us from an async onComplete callback that cannot
        // recover a synchronous throw out of its chain. Vert.x Kafka dispatches each record on a
        // duplicated context, so the substrate's duplicated-context guard accepts the bind
        // (FR-CTX-174).
        ContextHolder.Scope durableScope = null;
        ContextHolder.Scope mdcScope = null;
        Future<Void> dispatchFuture;
        try {
            durableScope = inboundExecutionContextScope.installDurable(
                    DurableMetadataHeaderCodec.fromHeaders(headers), DispatchBoundary.KAFKA);
            // Scope the Kafka-specific MDC keys to this record's dispatch only. Vert.x Kafka
            // freshly duplicates the consumer's stream context per record (see
            // KafkaReadStreamImpl.run() in vertx-kafka-client 5.x — `ctx = stream.context.duplicate()`
            // before `ctx.emit(...)`), so each record's holder writes (including MDCContext,
            // which MDCContexts.bindAll stores in the per-context ContextHolder slot) are
            // isolated by Vert.x context identity. The bindAll/eventually() scoping is therefore
            // not needed to prevent cross-record holder bleed — it is needed because
            // MDCContexts.bindAll is the canonical mutate-and-restore API for MDC keys
            // (capturing prior per-key state on install and restoring it, including absence, on
            // scope close), which keeps each record's MDC additions confined to its own dispatch
            // even when an upstream caller had its own MDC keys bound on the duplicate's prior
            // state. The MDC service-dispatch encoder registered by LoggingContextModule (via
            // ServiceDispatchCodecs.snapshotEncoder) picks the bound MDCContext up into the
            // outgoing envelope via the standard encoder pipeline.
            mdcScope = MDCContexts.bindAll(Map.of(
                    MDC_CONSUMER, entry.name(),
                    MDC_TOPIC, recordContext.topic(),
                    MDC_PARTITION, String.valueOf(recordContext.partition()),
                    MDC_OFFSET, String.valueOf(recordContext.offset()),
                    MDC_CORRELATION_ID, correlationId));
            Map<String, Object> callerOverrides = Map.of(KafkaRecordContext.class.getName(), recordContext);
            DispatchEnvelope<?> body = envelopeBuilder.build(value, callerOverrides, DispatchBoundary.KAFKA);

            boolean isAutoCommit = entry.config().commitStrategy() == CommitStrategy.AUTO;

            if (isAutoCommit || oneWay) {
                // Fire-and-forget: no reply expected
                if (stableTargetId != null) {
                    ResolvedServiceTarget target =
                            resolvedTargets.computeIfAbsent(stableTargetId, targetResolver::resolve);
                    dispatchFuture = requestSender.sendOneWay(target, body);
                } else {
                    eventBusClient.send(targetAddress, body);
                    dispatchFuture = Future.succeededFuture();
                }
            } else if (stableTargetId != null) {
                // Request-reply with stable target
                ResolvedServiceTarget target = resolvedTargets.computeIfAbsent(stableTargetId, targetResolver::resolve);
                dispatchFuture = requestSender
                        .send(target, body, entry.config().eventBusTimeoutMs())
                        .compose(Futures::toFuture)
                        .mapEmpty();
            } else {
                // Request-reply by address
                dispatchFuture = requestSender
                        .send(targetAddress, body, entry.config().eventBusTimeoutMs())
                        .compose(Futures::toFuture)
                        .mapEmpty();
            }
        } catch (RuntimeException e) {
            if (mdcScope != null) {
                mdcScope.close();
            }
            if (durableScope != null) {
                durableScope.close();
            }
            return Future.failedFuture(e);
        }

        // Close MDC + durable scopes in every terminal branch (FR-CTX-157). MDC closes first so
        // its restoration sees the durable scope's bindings still in place — symmetric with the
        // bind order above.
        final ContextHolder.Scope finalMdcScope = mdcScope;
        final ContextHolder.Scope finalDurableScope = durableScope;
        return dispatchFuture.eventually(() -> {
            finalMdcScope.close();
            finalDurableScope.close();
            return Future.succeededFuture();
        });
    }

    /**
     * Dispatches the deserialized value directly to the {@link KafkaRecordHandler}.
     *
     * @param value the deserialized payload value
     * @param record the original consumer record (for metadata)
     * @param headers the already-extracted header map
     * @return a future that completes when the handler has processed the record
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Future<Void> dispatchToHandler(
            Object value, KafkaConsumerRecord<String, byte[]> record, Map<String, String> headers) {
        KafkaRecordHandler handler = entry.handler();
        if (handler == null) {
            return Future.failedFuture("No handler configured for HANDLER-kind consumer: " + entry.name());
        }
        KafkaMessage<?> message = new KafkaMessage<>(
                value, record.key(), record.topic(), record.partition(), record.offset(), record.timestamp(), headers);
        // Vert.x Kafka dispatches each record on a duplicated context, so we bind durable header
        // metadata into the holder for the handler invocation. Handlers that produce downstream
        // messages or call services observe the same DurablePropagationMetadata and decoded
        // typed values as the event-bus dispatch path (FR-CTX-174). Going through
        // InboundExecutionContextScope.installDurable also runs registered
        // InboundContextInitializers (including the correlation seeder), so handlers see a bound
        // CorrelationContext even when the inbound record carries no encoded one (FR-COR-125).
        // installDurable is invoked inside the same defensive try block as the handler call so
        // any synchronous failure — including a duplicated-context invariant violation —
        // converts to a failed future instead of escaping the async callback.
        ContextHolder.Scope scope = null;
        Future<Void> handlerFuture;
        try {
            scope = inboundExecutionContextScope.installDurable(
                    DurableMetadataHeaderCodec.fromHeaders(headers), DispatchBoundary.KAFKA);
            handlerFuture = handler.handle(message);
            if (handlerFuture == null) {
                scope.close();
                return Future.failedFuture(new NullPointerException(
                        "KafkaRecordHandler " + handler.getClass().getName() + " returned null for consumer "
                                + entry.name() + "; handler contract requires a Future"));
            }
        } catch (RuntimeException e) {
            if (scope != null) {
                scope.close();
            }
            return Future.failedFuture(e);
        }
        final ContextHolder.Scope finalScope = scope;
        return handlerFuture.eventually(() -> {
            finalScope.close();
            return Future.succeededFuture();
        });
    }

    // --- Inner types ---

    /**
     * Result of route resolution combining the matched route and an optionally pre-decoded routing
     * value. The routing value is present when property-based matching was used, carrying the
     * format's intermediate representation (a parsed object tree, a decoded record, etc.) so that
     * {@link #deserializeRecord} can convert it to the route's target type via
     * the SPI without a second wire-byte parse.
     *
     * @param route the matched route entry
     * @param routingValue the value produced by the routing deserializer (property match), or
     *     {@code null} for header/default matches
     */
    record RouteResult(ConsumerEntry.RouteEntry route, Object routingValue) {}
}
