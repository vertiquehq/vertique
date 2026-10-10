// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.core.util.Strings;
import dev.vertique.kafka.KafkaConfigHelper;
import dev.vertique.kafka.KafkaDlqHeaders;
import dev.vertique.kafka.KafkaRecordHeader;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaProducerConfig;
import dev.vertique.kafka.config.KafkaProducerMethodConfig;
import dev.vertique.kafka.config.KafkaSecretKeys;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating typed Kafka producer proxies and providing raw send capability
 * for internal use (e.g., dead-letter queue publishing, outbox relay).
 *
 * <p>Producer proxies are JDK dynamic proxies backed by the Vert.x Kafka producer client.
 * Each proxy method serializes the value via {@link KafkaSerializer} and publishes to the
 * topic declared by {@link Topic}.
 *
 * <p>The shared underlying Vert.x {@code io.vertx.kafka.client.producer.KafkaProducer} is
 * created lazily on first use and reused for all proxies and raw sends.
 *
 * <p>Record headers are {@link KafkaRecordHeaders}: an ordered list that keeps repeated keys and
 * binary values. Before constructing each {@link KafkaProducerRecord}, the currently bound context
 * values are captured and projected to framework context headers. The record carries the
 * application headers in the order they were given, followed by one text header per context
 * namespace; the order among the context headers is unspecified. A send fails with an
 * {@link IllegalArgumentException} when an application header uses the
 * {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix, which is kept for the framework, or
 * when a header has a {@code null} value, which a producer record cannot carry.
 *
 * <p>The dead-letter send is the exception:
 * {@link #sendForDlq(String, String, byte[], KafkaRecordHeaders)} forwards the headers it is given
 * verbatim and captures no context.
 *
 * <p>Every send converges at
 * {@link #sendWire(String, String, byte[], KafkaRecordHeaders, KafkaSendOrigin, KafkaProducerOperation, String)},
 * which fires all registered {@link KafkaProducerCaptureHook} instances (observer-only) after the
 * underlying send settles. Hooks are sorted by {@link OrderedExtension#comparator()} and isolated
 * via try/catch: an {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by one
 * hook is logged and swallowed, so it affects neither the send result nor the hooks after it. An
 * {@link Exception} or {@link AssertionError} is logged at warn level each time. A
 * {@link LinkageError} means the hook cannot run at all and would repeat for every send, so it is
 * logged at error level once per hook class for the life of the factory.
 */
@Slf4j
public class KafkaProducerFactory {

    // --- Dependencies ---

    private final Vertx vertx;
    private final KafkaConfig kafkaConfig;
    private final Map<String, KafkaProducerConfig> producerIndex;
    private final DurableContextPropagator propagator;
    private final KafkaSerdeRegistry serdeRegistry;

    /** Sorted list of registered producer capture hooks (observer-only). */
    private final List<KafkaProducerCaptureHook> captureHooks;

    // --- Runtime state ---

    /** Reports the swallowed failures of capture hooks; safe on any thread, as a send settles on any. */
    private final ObserverFailureReporter hookFailureReporter =
            new ObserverFailureReporter(log, "Kafka producer capture hook");

    private final AtomicReference<io.vertx.kafka.client.producer.KafkaProducer<String, byte[]>> shared =
            new AtomicReference<>();

    /**
     * Per-method serializers built across all proxies, closed on {@link #close()} so registry-backed
     * serdes (e.g. Avro Schema Registry clients) are released.
     */
    private final List<KafkaSerializer<Object>> builtSerializers = Collections.synchronizedList(new ArrayList<>());

    // --- Constructors ---

    /**
     * Creates a new producer factory with no capture hooks (no-op default).
     *
     * @param vertx         the Vert.x instance used to create the Kafka producer client
     * @param kafkaConfig   the typed {@code kafka} config (supplies the producer index, global format,
     *                      schema registry, and the connection / global-producer property bags)
     * @param propagator    the durable context propagator used to merge captured metadata into outgoing
     *                      record headers (FR-CTX-173)
     * @param serdeRegistry the value-format serde registry used to select a per-method serializer
     */
    public KafkaProducerFactory(
            Vertx vertx,
            KafkaConfig kafkaConfig,
            DurableContextPropagator propagator,
            KafkaSerdeRegistry serdeRegistry) {
        this(vertx, kafkaConfig, propagator, serdeRegistry, Set.of());
    }

    /**
     * Creates a new producer factory with the supplied set of capture hooks.
     *
     * @param vertx         the Vert.x instance used to create the Kafka producer client
     * @param kafkaConfig   the typed {@code kafka} config (supplies the producer index, global format,
     *                      schema registry, and the connection / global-producer property bags)
     * @param propagator    the durable context propagator used to merge captured metadata into outgoing
     *                      record headers (FR-CTX-173)
     * @param serdeRegistry the value-format serde registry used to select a per-method serializer
     * @param captureHooks  the set of producer capture hooks; sorted by {@link OrderedExtension#comparator()}
     *                      at construction time
     */
    public KafkaProducerFactory(
            Vertx vertx,
            KafkaConfig kafkaConfig,
            DurableContextPropagator propagator,
            KafkaSerdeRegistry serdeRegistry,
            Set<KafkaProducerCaptureHook> captureHooks) {
        this.vertx = vertx;
        this.kafkaConfig = kafkaConfig;
        this.producerIndex = kafkaConfig.producerIndex();
        this.propagator = propagator;
        this.serdeRegistry = serdeRegistry;
        List<KafkaProducerCaptureHook> sorted = new ArrayList<>(captureHooks);
        sorted.sort(OrderedExtension.comparator());
        this.captureHooks = Collections.unmodifiableList(sorted);
    }

    // --- Proxy creation ---

    /**
     * Creates a typed proxy for a {@link KafkaProducer @KafkaProducer} interface.
     *
     * <p>Each method has one of four parameter shapes:
     * <ol>
     *   <li>{@code (V value)} — value only (key is {@code null})</li>
     *   <li>{@code (String key, V value)} — key + value</li>
     *   <li>{@code (V value, KafkaRecordHeaders headers)} — value + headers</li>
     *   <li>{@code (String key, V value, KafkaRecordHeaders headers)} — key + value + headers</li>
     * </ol>
     *
     * <p>The shape is resolved from the parameter types: a last parameter of type
     * {@link KafkaRecordHeaders} is the headers; of the one or two parameters that remain, the last
     * is the value and a leading {@code String} is the key. So {@code (String, KafkaRecordHeaders)}
     * is a {@code String} value with headers, and {@code (String, Map)} is a key with a {@code Map}
     * value. A {@code null} headers argument at call time means no headers.
     *
     * <p>Every other shape is rejected here, when the proxy is created: a {@code Map} in the
     * position of the headers (build the headers with {@link KafkaRecordHeaders#of(Map)} instead),
     * a value of type {@link KafkaRecordHeaders}, no parameters, more than three, or two leading
     * parameters whose first is not a {@code String}.
     *
     * <p>Producer method names must be unique: the topic and the serializer of a method are
     * configured by its name, so two methods with the same name and different parameters are
     * rejected here as well.
     *
     * @param <T> the producer interface type
     * @param producerInterface the interface class annotated with {@link KafkaProducer}
     * @return a proxy instance implementing {@code producerInterface}
     * @throws IllegalArgumentException if the interface is not annotated with {@link KafkaProducer},
     *     a method is missing a {@link Topic} annotation, a method has a parameter shape that is not
     *     one of the four, or two methods have the same name; the message names the method or
     *     methods
     * @throws RuntimeException if a {@link KafkaProducerCaptureHook} rejects the interface in
     *     {@link KafkaProducerCaptureHook#validateProducer(Class)}
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> producerInterface) {
        KafkaProducer annotation = producerInterface.getAnnotation(KafkaProducer.class);
        if (annotation == null) {
            throw new IllegalArgumentException(producerInterface.getName() + " is not annotated with @KafkaProducer");
        }
        String producerName = annotation.name().isEmpty() ? producerInterface.getSimpleName() : annotation.name();

        for (KafkaProducerCaptureHook hook : captureHooks) {
            hook.validateProducer(producerInterface);
        }

        KafkaProducerConfig producerConfig = producerIndex.get(producerName);

        rejectOverloadedMethods(producerInterface);
        Map<String, String> methodTopics = resolveMethodTopics(producerInterface, producerConfig);
        // Resolved before any serializer is built, so a method with an unsupported shape fails the
        // proxy creation without leaving a serializer open.
        Map<Method, MethodShape> shapesByMethod = new HashMap<>();
        for (Method method : producerInterface.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                shapesByMethod.put(method, resolveShape(method));
            }
        }
        Map<Method, MethodShape> shapes = Map.copyOf(shapesByMethod);
        Map<String, KafkaSerializer<Object>> methodSerializers =
                resolveMethodSerializers(producerInterface, producerName, producerConfig, kafkaConfig, serdeRegistry);
        builtSerializers.addAll(methodSerializers.values());

        Map<Method, KafkaProducerOperation> operationsByMethod = new HashMap<>();
        for (Method method : producerInterface.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                operationsByMethod.put(method, new KafkaProducerOperation(producerInterface, producerName, method));
            }
        }
        Map<Method, KafkaProducerOperation> operations = Map.copyOf(operationsByMethod);

        return (T) Proxy.newProxyInstance(
                producerInterface.getClassLoader(), new Class<?>[] {producerInterface}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args, producerInterface);
                    }
                    String topic = methodTopics.get(method.getName());
                    MethodShape shape = shapes.get(method);
                    String key = shape.keyIndex() < 0 ? null : (String) args[shape.keyIndex()];
                    Object value = args[shape.valueIndex()];
                    KafkaRecordHeaders given =
                            shape.headersIndex() < 0 ? null : (KafkaRecordHeaders) args[shape.headersIndex()];
                    // The serializer and the send are given the same instance, never null.
                    KafkaRecordHeaders headers = given == null ? KafkaRecordHeaders.empty() : given;

                    KafkaSerializer<Object> serializer = methodSerializers.get(method.getName());
                    // Event-loop safety: a mayBlock serializer (e.g. Avro on a registry cache miss) is
                    // offloaded to the worker pool; non-blocking formats stay on-loop. Either way a
                    // serialization failure becomes a failed Future, never a synchronous throw out of
                    // the proxy: executeBlocking captures the throw, and the on-loop branch is wrapped
                    // below.
                    if (serializer.mayBlock()) {
                        final KafkaProducerOperation fOperation = operations.get(method);
                        return vertx.executeBlocking(() -> serializer.serialize(value, topic, headers), false)
                                .compose(bytes -> sendRaw(
                                        topic, key, bytes, headers, KafkaSendOrigin.DIRECT_PRODUCER, fOperation));
                    }
                    try {
                        byte[] bytes = serializer.serialize(value, topic, headers);
                        return sendRaw(
                                topic, key, bytes, headers, KafkaSendOrigin.DIRECT_PRODUCER, operations.get(method));
                    } catch (RuntimeException e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    // --- Public send API ---

    /**
     * Sends a raw byte array message to the specified topic, capturing ambient context from the
     * current {@link ContextHolder} scope into framework context headers. For direct send callers
     * that want ambient context propagated automatically.
     *
     * <p>The record carries the application headers in the order given, with repeated keys and
     * binary values kept, followed by one text header per context namespace.
     *
     * <p>This overload uses origin {@link KafkaSendOrigin#INTERNAL}. Prefer the origin-explicit
     * overloads for callers that know their send origin.
     *
     * @param topic   the target topic
     * @param key     the message key, or {@code null}
     * @param value   the raw message bytes
     * @param headers the application headers, or {@code null} for none; build them from a text map
     *                with {@link KafkaRecordHeaders#of(Map)}; must not use the
     *                {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix and must not contain
     *                a header with a {@code null} value
     * @return a future of the record metadata; fails with {@link IllegalArgumentException}, naming
     *     the key, if an application header uses the reserved framework prefix or has a
     *     {@code null} value
     */
    public Future<RecordMetadata> send(String topic, String key, byte[] value, KafkaRecordHeaders headers) {
        return sendRaw(topic, key, value, headers, KafkaSendOrigin.INTERNAL, null);
    }

    /**
     * Sends a raw byte array message to the specified topic using an explicitly supplied
     * {@link DurableMetadata} context instead of capturing ambient context. Intended for relay
     * callers (e.g. outbox→Kafka relay) that already hold a deserialized durable document and must
     * NOT capture the ambient event-loop context, which would be the relay's own context rather
     * than the original producer's.
     *
     * <p>This overload uses origin {@link KafkaSendOrigin#INTERNAL}. Prefer
     * {@link #sendForOutbox(String, String, byte[], KafkaRecordHeaders, DurableMetadata)} when the
     * caller is the outbox relay.
     *
     * <p>The record carries the application headers in the order given, with repeated keys and
     * binary values kept, followed by one text header per namespace of the supplied
     * {@code context}.
     *
     * @param topic    the target topic
     * @param key      the message key, or {@code null}
     * @param value    the raw message bytes
     * @param headers  the application headers, or {@code null} for none; must not use the
     *                 {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix and must not contain
     *                 a header with a {@code null} value
     * @param context  the explicit durable context to project into headers; must not be {@code null}
     * @return a future of the record metadata; fails with {@link IllegalArgumentException}, naming
     *     the key, if an application header uses the reserved framework prefix or has a
     *     {@code null} value
     */
    public Future<RecordMetadata> send(
            String topic, String key, byte[] value, KafkaRecordHeaders headers, DurableMetadata context) {
        return sendWithContext(topic, key, value, headers, context, KafkaSendOrigin.INTERNAL, null, null);
    }

    /**
     * Republishes a failed record to a dead-letter topic. This is the framework's dead-letter path,
     * called by the consumer error handling; it is not for application sends.
     *
     * <p>The headers are forwarded verbatim, in their order and with repeated keys and binary values
     * kept, so the dead-letter record keeps the failed record's own context. Unlike every other send
     * on this class:
     *
     * <ul>
     *   <li>a header with the {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix is
     *       <strong>not</strong> rejected: the framework context headers the failed record carried
     *       are forwarded as they were received;
     *   <li>the ambient context of the current scope is <strong>not</strong> captured or overlaid:
     *       it is not the failed record's context, and no context header that was not given is
     *       added.
     * </ul>
     *
     * <p>Fires capture hooks with origin {@link KafkaSendOrigin#DLQ} and a {@code null} producer
     * operation. Application code must use {@link #send(String, String, byte[], KafkaRecordHeaders)}
     * or a {@link KafkaProducer @KafkaProducer} proxy, which keep the reserved prefix for the
     * framework.
     *
     * <p>To keep an application send from taking this path by mistake, the headers must contain the
     * {@link KafkaDlqHeaders#SOURCE_TOPIC} header the error handling writes on every dead-letter
     * record; a send without it fails.
     *
     * @param topic   the dead-letter topic
     * @param key     the record key, or {@code null}
     * @param value   the raw record bytes to republish
     * @param headers the headers of the dead-letter record, forwarded verbatim; must contain the
     *                {@link KafkaDlqHeaders#SOURCE_TOPIC} header and must not contain a header with a
     *                {@code null} value
     * @return a future of the record metadata; fails with {@link IllegalArgumentException} if a
     *     header has a {@code null} value, naming the key, or if the headers are {@code null} or
     *     lack the {@link KafkaDlqHeaders#SOURCE_TOPIC} header
     */
    public Future<RecordMetadata> sendForDlq(String topic, String key, byte[] value, KafkaRecordHeaders headers) {
        KafkaRecordHeaders wire = headers == null ? KafkaRecordHeaders.empty() : headers;
        try {
            rejectNullValues(wire);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        if (wire.lastHeader(KafkaDlqHeaders.SOURCE_TOPIC).isEmpty()) {
            return Future.failedFuture(
                    new IllegalArgumentException("sendForDlq is the framework's dead-letter path and requires the '"
                            + KafkaDlqHeaders.SOURCE_TOPIC + "' header; application sends must use send"));
        }
        return sendWire(topic, key, value, wire, KafkaSendOrigin.DLQ, null);
    }

    /**
     * Sends a raw byte array message from the outbox relay using an explicitly supplied
     * {@link DurableMetadata} context. Fires capture hooks with origin
     * {@link KafkaSendOrigin#OUTBOX} and a {@code null} producer method.
     *
     * <p>This form carries no origin reference: capture hooks see a {@code null}
     * {@link KafkaProducerSend#originRef()}. A caller that knows the outbox entry id uses
     * {@link #sendForOutbox(String, String, byte[], KafkaRecordHeaders, DurableMetadata, String)},
     * so a hook can tell which entry the bytes belong to.
     *
     * <p>The record carries the application headers in the order given, followed by one text header
     * per namespace of the supplied {@code context}.
     *
     * @param topic   the target Kafka topic
     * @param key     the record key, or {@code null}
     * @param value   the raw message bytes
     * @param headers the application headers, or {@code null} for none; must not use the
     *                {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix and must not contain
     *                a header with a {@code null} value
     * @param context the durable context stored in the outbox entry; must not be {@code null}
     * @return a future of the record metadata; fails with {@link IllegalArgumentException}, naming
     *     the key, if an application header uses the reserved framework prefix or has a
     *     {@code null} value
     */
    public Future<RecordMetadata> sendForOutbox(
            String topic, String key, byte[] value, KafkaRecordHeaders headers, DurableMetadata context) {
        return sendWithContext(topic, key, value, headers, context, KafkaSendOrigin.OUTBOX, null, null);
    }

    /**
     * Sends a raw byte array message from the outbox relay for one outbox entry, using an explicitly
     * supplied {@link DurableMetadata} context. Fires capture hooks with origin
     * {@link KafkaSendOrigin#OUTBOX}, a {@code null} producer method, and the entry id as
     * {@link KafkaProducerSend#originRef()}.
     *
     * <p>Use this overload from the outbox destination handler so the record's persisted durable
     * context (not the relay poller's ambient context) crosses the wire boundary, and so a capture
     * hook can join the wire bytes to the outbox entry they belong to. The entry id is not put on
     * the record; it is handed to the hooks only.
     *
     * <p>The record carries the application headers in the order given, followed by one text header
     * per namespace of the supplied {@code context}.
     *
     * <p>The origin reference is not authenticated: any code that holds this factory can call this
     * method and so send with origin {@link KafkaSendOrigin#OUTBOX} and an arbitrary reference. A
     * hook consumer that joins on {@link KafkaProducerSend#originRef()} must therefore also match a
     * relay notification for the same entry id and destination before it trusts the join.
     *
     * @param topic   the target Kafka topic
     * @param key     the record key, or {@code null}
     * @param value   the raw message bytes
     * @param headers the application headers, or {@code null} for none; must not use the
     *                {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix and must not contain
     *                a header with a {@code null} value
     * @param context the durable context stored in the outbox entry; must not be {@code null}
     * @param entryId the id of the outbox entry being sent, as a string; must not be {@code null}
     * @return a future of the record metadata; fails with {@link IllegalArgumentException}, naming
     *     the key, if an application header uses the reserved framework prefix or has a
     *     {@code null} value
     * @throws NullPointerException if {@code entryId} is {@code null}
     */
    public Future<RecordMetadata> sendForOutbox(
            String topic,
            @Nullable String key,
            byte[] value,
            KafkaRecordHeaders headers,
            DurableMetadata context,
            String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return sendWithContext(topic, key, value, headers, context, KafkaSendOrigin.OUTBOX, null, entryId);
    }

    // --- Lifecycle ---

    /**
     * Closes the shared Kafka producer and all per-method serializers (releasing any registry-backed
     * serde clients, e.g. Avro Schema Registry connections).
     *
     * @return a future that completes when the producer is closed
     */
    public Future<Void> close() {
        synchronized (builtSerializers) {
            for (KafkaSerializer<Object> serializer : builtSerializers) {
                try {
                    serializer.close();
                } catch (RuntimeException e) {
                    log.warn("Error closing Kafka producer serializer: {}", e.getMessage(), e);
                }
            }
            builtSerializers.clear();
        }
        io.vertx.kafka.client.producer.KafkaProducer<String, byte[]> p = shared.get();
        if (p != null) {
            return p.close();
        }
        return Future.succeededFuture();
    }

    // --- Internal ---

    /**
     * Resolves the effective topic name for each method, applying the per-method config override from
     * {@code kafka.producers.{name}.methods.{method}.topic} when present.
     *
     * @param producerInterface the producer interface
     * @param producerConfig the typed per-producer config, or {@code null} when the producer is not
     *     configured (every method then uses its {@link Topic} annotation value)
     * @return map of method name to effective topic
     * @throws IllegalArgumentException if a method is missing a {@link Topic} annotation
     */
    private static Map<String, String> resolveMethodTopics(
            Class<?> producerInterface, @Nullable KafkaProducerConfig producerConfig) {
        Map<String, KafkaProducerMethodConfig> methodIndex = methodIndex(producerConfig);
        Map<String, String> methodTopics = new HashMap<>();
        for (Method method : producerInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Topic topicAnn = method.getAnnotation(Topic.class);
            if (topicAnn == null) {
                throw new IllegalArgumentException("Method " + method.getName() + " on "
                        + producerInterface.getSimpleName() + " is missing @Topic");
            }
            KafkaProducerMethodConfig methodConfig = methodIndex.get(method.getName());
            String topic =
                    methodConfig != null && methodConfig.topic() != null ? methodConfig.topic() : topicAnn.value();
            methodTopics.put(method.getName(), topic);
        }
        return methodTopics;
    }

    /**
     * Builds the {@code method -> KafkaProducerMethodConfig} index for a producer, from the keyed
     * {@code methods} list. Returns an empty map for an unconfigured producer.
     *
     * @param producerConfig the typed per-producer config, or {@code null}
     * @return an immutable index keyed by method name (possibly empty)
     */
    private static Map<String, KafkaProducerMethodConfig> methodIndex(@Nullable KafkaProducerConfig producerConfig) {
        if (producerConfig == null) {
            return Map.of();
        }
        Map<String, KafkaProducerMethodConfig> index = new LinkedHashMap<>();
        for (KafkaProducerMethodConfig method : producerConfig.methods()) {
            index.put(method.method(), method);
        }
        return index;
    }

    /**
     * Resolves the per-method value serializer, mirroring {@link #resolveMethodTopics}. The format is
     * selected per method via {@link KafkaSerdeRegistry#resolveFormat} with the producer precedence
     * <strong>{@code producers.{name}.{method}.format} &gt; {@code producers.{name}.format} &gt;
     * {@code kafka.format} &gt; auto-detect(method value type) &gt; json</strong> (FR-AVRO-003), so a
     * producer interface may mix formats across methods. The serde config handed to the provider is
     * the already-merged view {@code method.serdeProperties &gt; producer.serdeProperties} plus the
     * canonical {@code kafka.schemaRegistry} (FR-AVRO-009). Requesting a format with no registered
     * provider fails fast here, naming the endpoint (FR-AVRO-010).
     *
     * @param producerInterface the producer interface
     * @param producerName the resolved producer name (for error messages)
     * @param producerConfig the typed per-producer config, or {@code null} when the producer is not
     *     configured (no producer/method format overrides or serde properties apply)
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format and schema registry)
     * @param serdeRegistry the serde registry
     * @return map of method name to its selected serializer
     */
    static Map<String, KafkaSerializer<Object>> resolveMethodSerializers(
            Class<?> producerInterface,
            String producerName,
            @Nullable KafkaProducerConfig producerConfig,
            KafkaConfig kafkaConfig,
            KafkaSerdeRegistry serdeRegistry) {
        String globalFormat = kafkaConfig.format();
        JsonObject schemaRegistry =
                kafkaConfig.schemaRegistry() != null ? kafkaConfig.schemaRegistry() : new JsonObject();
        JsonObject producerSerde = producerConfig != null && producerConfig.serdeProperties() != null
                ? producerConfig.serdeProperties()
                : new JsonObject();
        String producerFormat = producerConfig != null ? producerConfig.format() : null;
        String producerConfigProfile = producerConfig != null ? producerConfig.jsonProfile() : null;
        // FR-JSON-066: @JsonProfile selects a profile for the whole producer and must be placed at TYPE
        // level. A method-level placement is rejected once at producer-build time (before the serializer
        // loop), so it fails even if that method has no serde work.
        rejectMethodLevelJsonProfile(producerInterface);
        // The annotation-tier profile is the lowest-precedence non-vertx default; resolve it once outside
        // the loop (FR-JSON-036C) from the type-level @JsonProfile annotation (a blank or absent value
        // means "no annotation default").
        String producerAnnProfile = resolveAnnotationProfile(producerInterface);
        Map<String, KafkaProducerMethodConfig> methodIndex = methodIndex(producerConfig);

        Map<String, KafkaSerializer<Object>> serializers = new HashMap<>();
        try {
            for (Method method : producerInterface.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                Class<?> valueType = resolveValueType(method);
                KafkaProducerMethodConfig methodConfig = methodIndex.get(method.getName());
                String methodFormat = methodConfig != null ? methodConfig.format() : null;
                JsonObject methodSerde = methodConfig != null && methodConfig.serdeProperties() != null
                        ? methodConfig.serdeProperties()
                        : new JsonObject();
                String explicitFormat = Strings.firstNonBlank(methodFormat, producerFormat);
                JsonObject formatLookup =
                        explicitFormat == null ? new JsonObject() : new JsonObject().put("format", explicitFormat);
                String format;
                try {
                    format = serdeRegistry.resolveFormat(valueType, formatLookup, globalFormat);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Producer " + producerName + "#" + method.getName() + " cannot resolve value format: "
                                    + e.getMessage(),
                            e);
                }
                // Effective JSON profile: method config > producer config > @KafkaProducer annotation >
                // kafka.jsonProfile boundary default > vertx (FR-JSON-036C / FR-JSON-052).
                // The json.jsonProfile global tier and the vertx floor are applied in kafka-json
                // (JsonSerdeProvider.resolveMapper), which sees the bag id via the serde config.
                // Only the JSON serde provider reads it; non-JSON providers ignore it.
                String methodProfile = methodConfig != null ? methodConfig.jsonProfile() : null;
                String effectiveProfile = Strings.firstNonBlank(
                        methodProfile, producerConfigProfile, producerAnnProfile, kafkaConfig.jsonProfile());
                JsonObject serdeConfig = buildSerdeConfig(schemaRegistry, producerSerde, methodSerde, effectiveProfile);
                @SuppressWarnings("unchecked")
                Class<Object> erased = (Class<Object>) valueType;
                try {
                    serializers.put(method.getName(), serdeRegistry.serializer(format, erased, serdeConfig));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Producer " + producerName + "#" + method.getName() + " requests value format '" + format
                                    + "': " + e.getMessage(),
                            e);
                }
            }
        } catch (RuntimeException e) {
            // A later method's serde build failed — close the serializers already built in this loop
            // (not yet registered for close()) so a doomed create() does not leak registry clients.
            closeQuietly(serializers.values(), e);
            throw e;
        }
        return serializers;
    }

    private static void closeQuietly(Iterable<KafkaSerializer<Object>> serializers, RuntimeException primary) {
        for (KafkaSerializer<Object> serializer : serializers) {
            try {
                serializer.close();
            } catch (RuntimeException closeFailure) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * Resolves the declared value parameter type for a producer method, from the same shape
     * resolution {@link #create(Class)} uses.
     *
     * @param method the producer method
     * @return the value parameter type
     * @throws IllegalArgumentException if the method does not have one of the supported shapes
     */
    static Class<?> resolveValueType(Method method) {
        return resolveShape(method).valueType();
    }

    /**
     * The positions of the key, the value and the headers among the parameters of a producer method.
     *
     * @param keyIndex     the index of the {@code String} key parameter, or {@code -1} when the
     *                     method has none
     * @param valueIndex   the index of the value parameter
     * @param headersIndex the index of the {@link KafkaRecordHeaders} parameter, or {@code -1} when
     *                     the method has none
     * @param valueType    the declared type of the value parameter
     */
    private record MethodShape(int keyIndex, int valueIndex, int headersIndex, Class<?> valueType) {}

    /**
     * Resolves the parameter shape of a producer method. This is the only place that decides which
     * parameter is the key, the value and the headers; proxy creation and serializer selection both
     * use it.
     *
     * <p>A last parameter of type {@link KafkaRecordHeaders} is the headers. Of the one or two
     * parameters that remain, the last is the value and a leading {@code String} is the key.
     *
     * @param method the producer method
     * @return the resolved shape
     * @throws IllegalArgumentException naming the method, if it has a {@code Map} in the position of
     *     the headers, a {@link KafkaRecordHeaders} value, or any other shape that is not one of the
     *     four supported ones
     */
    private static MethodShape resolveShape(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        int count = paramTypes.length;
        boolean hasHeaders = count > 0 && paramTypes[count - 1] == KafkaRecordHeaders.class;
        // (String, Map) is a key and a Map value; a Map last in any other shape of two or three
        // parameters stands where the headers go.
        boolean keyAndMapValue = count == 2 && paramTypes[0] == String.class;
        if (!hasHeaders
                && count >= 2
                && count <= 3
                && Map.class.isAssignableFrom(paramTypes[count - 1])
                && !keyAndMapValue) {
            throw new IllegalArgumentException("Method " + describe(method) + " takes a Map as record headers; declare"
                    + " the parameter as KafkaRecordHeaders and build the argument with KafkaRecordHeaders.of(Map)");
        }
        int remaining = hasHeaders ? count - 1 : count;
        boolean valueOnly = remaining == 1;
        boolean keyAndValue = remaining == 2 && paramTypes[0] == String.class;
        if (!valueOnly && !keyAndValue) {
            throw new IllegalArgumentException("Method " + describe(method) + " has an unsupported parameter shape;"
                    + " supported shapes are (V value), (String key, V value), (V value, KafkaRecordHeaders headers)"
                    + " and (String key, V value, KafkaRecordHeaders headers)");
        }
        int valueIndex = remaining - 1;
        Class<?> valueType = paramTypes[valueIndex];
        if (valueType == KafkaRecordHeaders.class) {
            throw new IllegalArgumentException("Method " + describe(method) + " has a value of type KafkaRecordHeaders;"
                    + " KafkaRecordHeaders is accepted only as the last parameter, after the value");
        }
        return new MethodShape(keyAndValue ? 0 : -1, valueIndex, hasHeaders ? count - 1 : -1, valueType);
    }

    /**
     * Rejects a producer interface that has two methods with the same name and different parameters.
     * The topic and the serializer of a method are configured by its name, so such methods would
     * share one topic and one serializer.
     *
     * @param producerInterface the producer interface
     * @throws IllegalArgumentException naming the interface and both methods
     */
    private static void rejectOverloadedMethods(Class<?> producerInterface) {
        Map<String, Method> byName = new HashMap<>();
        for (Method method : producerInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Method other = byName.putIfAbsent(method.getName(), method);
            // The same signature inherited from two interfaces is one method, not an overload.
            if (other != null && !Arrays.equals(other.getParameterTypes(), method.getParameterTypes())) {
                // Sorted, so the message does not depend on reflection order.
                List<String> both = new ArrayList<>(List.of(describe(other), describe(method)));
                Collections.sort(both);
                throw new IllegalArgumentException(producerInterface.getName() + " has more than one method named '"
                        + method.getName() + "': " + both.get(0) + " and " + both.get(1)
                        + "; producer method names must be unique,"
                        + " because the topic and the serializer of a method are configured by its name");
            }
        }
    }

    /**
     * Describes a producer method for an error message: the declaring interface, the method name
     * and the parameter types.
     *
     * @param method the producer method
     * @return the description
     */
    private static String describe(Method method) {
        StringBuilder description = new StringBuilder(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            description.append(i == 0 ? "" : ", ").append(paramTypes[i].getSimpleName());
        }
        return description.append(')').toString();
    }

    /**
     * Builds the merged serde config view handed to a provider: {@code serdeProperties} merged
     * method-over-producer, plus the canonical {@code schemaRegistry} block, optionally carrying the
     * resolved JSON mapper profile id. The {@code jsonProfile} key is added only when the profile
     * is non-null/non-blank, so the {@code vertx}/default path sees a byte-for-byte unchanged bag.
     *
     * @param schemaRegistry the {@code kafka.schemaRegistry} block
     * @param producerSerde the producer-level {@code serdeProperties}
     * @param methodSerde the method-level {@code serdeProperties} (override)
     * @param jsonProfile the resolved JSON mapper profile id, or {@code null}/blank for the
     *     framework default ({@code vertx}); read only by the JSON serde provider
     * @return the merged endpoint serde config
     */
    private static JsonObject buildSerdeConfig(
            JsonObject schemaRegistry, JsonObject producerSerde, JsonObject methodSerde, @Nullable String jsonProfile) {
        JsonObject merged = producerSerde.copy();
        methodSerde.forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
        return KafkaConfigHelper.serdeConfig(schemaRegistry, merged, jsonProfile);
    }

    // --- @JsonProfile resolution (FR-JSON-066) ---

    /**
     * Resolves the producer-annotation-tier JSON mapper profile from the type-level {@link JsonProfile}
     * annotation. {@code @JsonProfile} is the sole per-binding profile selector: a non-blank value
     * selects that profile, and a blank or absent annotation resolves to {@code null} (no annotation
     * default — falls through to the config/kafka tiers).
     *
     * <p>This mirrors the consumer codegen lane ({@code KafkaListenerScanner.resolveEffectiveProfile})
     * and reflective lane ({@code KafkaConsumerScanner.resolveListenerJsonProfile}) so every boundary
     * applies the same selection semantics.
     *
     * @param producerInterface the {@code @KafkaProducer} interface; used to read the annotation
     * @return the effective annotation-tier profile id, or {@code null} for no annotation default
     */
    private static String resolveAnnotationProfile(Class<?> producerInterface) {
        JsonProfile annotation = producerInterface.getAnnotation(JsonProfile.class);
        if (annotation == null || annotation.value().isBlank()) {
            return null;
        }
        return annotation.value();
    }

    /**
     * Rejects a method-level {@link JsonProfile} on a {@code @KafkaProducer} interface (FR-JSON-066).
     * {@code @JsonProfile} selects a profile for the whole producer and must be placed at TYPE level; a
     * method-level placement fails fast at producer-build time, naming the offending method.
     *
     * <p>This mirrors the consumer codegen lane ({@code KafkaListenerScanner.rejectMethodLevelJsonProfile})
     * and reflective lane ({@code KafkaConsumerScanner.rejectMethodLevelJsonProfile}) so every boundary
     * applies identical placement rules.
     *
     * @param producerInterface the {@code @KafkaProducer} interface to scan
     * @throws IllegalStateException when any producer method carries a {@code @JsonProfile} annotation
     */
    private static void rejectMethodLevelJsonProfile(Class<?> producerInterface) {
        for (Method method : producerInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(JsonProfile.class) != null) {
                throw new IllegalStateException("@JsonProfile on method " + method.getName() + " in "
                        + producerInterface.getSimpleName()
                        + " is not allowed; place @JsonProfile at TYPE level on the @KafkaProducer interface");
            }
        }
    }

    /**
     * Sends a serialized record to Kafka, capturing ambient context via
     * {@link DurableContextPropagator#capture(String)} and appending it to the caller-supplied
     * headers as {@link #sendWithContext} does.
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param headers        optional caller-supplied application headers; may be {@code null}
     * @param origin         the send origin to thread through to the wire funnel
     * @param operation      the proxy's producer operation, or {@code null} for non-proxy origins
     * @return a future of the record metadata; FAILS with {@link IllegalArgumentException} if any
     *         application header uses the reserved framework prefix or has a {@code null} value
     *         (never throws synchronously for either, so callers can classify the failure in
     *         {@code recover})
     */
    private Future<RecordMetadata> sendRaw(
            String topic,
            String key,
            byte[] value,
            @Nullable KafkaRecordHeaders headers,
            KafkaSendOrigin origin,
            @Nullable KafkaProducerOperation operation) {
        DurableMetadata ctx = propagator.capture(DispatchBoundary.KAFKA);
        return sendWithContext(topic, key, value, headers, ctx, origin, operation, null);
    }

    /**
     * Sends a serialized record to Kafka using an explicitly supplied {@link DurableMetadata}
     * context. Does NOT call {@link DurableContextPropagator#capture} — the caller is responsible
     * for supplying the correct context (e.g. the outbox→Kafka relay, which holds the original
     * producer's context and must not capture the relay's own ambient context).
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param headers        optional caller-supplied application headers; may be {@code null}
     * @param context        the explicit durable context to project; must not be {@code null}
     * @param origin         the send origin to thread through to the wire funnel
     * @param operation      the proxy's producer operation, or {@code null} for non-proxy origins
     * @param originRef      the caller's reference to what the send carries (the outbox entry id),
     *                       or {@code null}
     * @return a future of the record metadata; FAILS with {@link IllegalArgumentException} if any
     *         application header uses the reserved framework prefix or has a {@code null} value
     *         (never throws synchronously for either)
     */
    private Future<RecordMetadata> sendWithContext(
            String topic,
            String key,
            byte[] value,
            @Nullable KafkaRecordHeaders headers,
            DurableMetadata context,
            KafkaSendOrigin origin,
            @Nullable KafkaProducerOperation operation,
            @Nullable String originRef) {
        final KafkaRecordHeaders wire;
        try {
            wire = egressHeaders(headers, context);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        // A send without an origin reference keeps going through the six-argument form, so a
        // subclass that overrides only that form still sees it.
        return originRef == null
                ? sendWire(topic, key, value, wire, origin, operation)
                : sendWire(topic, key, value, wire, origin, operation, originRef);
    }

    /**
     * Builds the headers of an outgoing record: the application headers in their order, followed by
     * one UTF-8 text header per namespace of the durable context. The order among the context
     * headers is unspecified.
     *
     * <p>This is where Kafka egress keeps the {@link DurableMetadataHeaderCodec#RESERVED_PREFIX}
     * prefix for the framework: an application header that uses it is rejected, so the context
     * headers can never share a key with an application header. The dead-letter send does not come
     * through here.
     *
     * @param headers the application headers, or {@code null} for none
     * @param context the durable context to project; must not be {@code null}
     * @return the wire headers; the application headers instance itself when the context projects
     *     to no header
     * @throws IllegalArgumentException if an application header uses the reserved framework prefix
     *     or has a {@code null} value
     */
    private static KafkaRecordHeaders egressHeaders(@Nullable KafkaRecordHeaders headers, DurableMetadata context) {
        Map<String, String> contextHeaders = DurableMetadataHeaderCodec.toHeaders(context);
        KafkaRecordHeaders application = headers == null ? KafkaRecordHeaders.empty() : headers;
        for (KafkaRecordHeader header : application) {
            if (DurableMetadataHeaderCodec.isReservedHeader(header.key())) {
                throw new IllegalArgumentException("Application header uses reserved framework prefix '"
                        + DurableMetadataHeaderCodec.RESERVED_PREFIX + "': " + header.key());
            }
        }
        rejectNullValues(application);
        if (contextHeaders.isEmpty()) {
            return application;
        }
        List<KafkaRecordHeader> wire = new ArrayList<>(application.entries().size() + contextHeaders.size());
        wire.addAll(application.entries());
        contextHeaders.forEach((name, body) -> wire.add(KafkaRecordHeader.ofUtf8(name, body)));
        return new KafkaRecordHeaders(wire);
    }

    /**
     * Rejects a header without a value. Kafka allows one, but the producer record this factory
     * sends cannot carry it.
     *
     * @param headers the headers about to be sent
     * @throws IllegalArgumentException naming the key, if a header has a {@code null} value
     */
    private static void rejectNullValues(KafkaRecordHeaders headers) {
        for (KafkaRecordHeader header : headers) {
            // hasValue() does not copy the buffer; the wire conversion makes the only copy.
            if (!header.hasValue()) {
                throw new IllegalArgumentException(
                        "Record header has a null value and cannot be sent: " + header.key());
            }
        }
    }

    /**
     * Sends a record that has no origin reference. Every such send is made through this method,
     * which delegates to
     * {@link #sendWire(String, String, byte[], KafkaRecordHeaders, KafkaSendOrigin, KafkaProducerOperation, String)}
     * with a {@code null} reference. A send that has an origin reference calls that form directly
     * and does not come through here.
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param wire           the wire headers in order (application headers, then context headers);
     *                       must not be {@code null} and must not contain a header with a
     *                       {@code null} value
     * @param origin         the send origin, threaded from the public entry point
     * @param operation      the producer operation for {@link KafkaSendOrigin#DIRECT_PRODUCER}, or
     *                       {@code null} for all other origins
     * @return a future of the record metadata; the future reflects the actual Kafka send result —
     *         hook exceptions do not change its outcome
     */
    protected Future<RecordMetadata> sendWire(
            String topic,
            String key,
            byte[] value,
            KafkaRecordHeaders wire,
            KafkaSendOrigin origin,
            @Nullable KafkaProducerOperation operation) {
        return sendWire(topic, key, value, wire, origin, operation, null);
    }

    /**
     * Creates the {@link KafkaProducerRecord}, adds every header in order, sends it via the shared
     * producer, and then fires all registered {@link KafkaProducerCaptureHook} instances after the
     * send settles.
     *
     * <p>Hook invocation is observer-only: hooks are called after the result is determined and
     * each hook is isolated in a {@code try/catch}, so a hook that throws an {@link Exception},
     * {@link LinkageError}, {@link AssertionError} or {@link StackOverflowError} never changes the
     * result.
     *
     * <p>A send without an origin reference fires the hooks through the seven-argument
     * {@code fireHooks}, so a subclass that overrides only that form still sees it; a send with an
     * origin reference fires them through the form that takes the reference.
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param wire           the wire headers in order (application headers, then context headers);
     *                       must not be {@code null} and must not contain a header with a
     *                       {@code null} value
     * @param origin         the send origin, threaded from the public entry point
     * @param operation      the producer operation for {@link KafkaSendOrigin#DIRECT_PRODUCER}, or
     *                       {@code null} for all other origins
     * @param originRef      the caller's reference to what the send carries (the outbox entry id),
     *                       handed to the hooks and not put on the record; {@code null} when the
     *                       send has none
     * @return a future of the record metadata; the future reflects the actual Kafka send result —
     *         hook exceptions do not change its outcome
     */
    protected Future<RecordMetadata> sendWire(
            String topic,
            String key,
            byte[] value,
            KafkaRecordHeaders wire,
            KafkaSendOrigin origin,
            @Nullable KafkaProducerOperation operation,
            @Nullable String originRef) {
        return getOrCreateProducer().compose(producer -> {
            KafkaProducerRecord<String, byte[]> record = KafkaProducerRecord.create(topic, key, value);
            for (KafkaRecordHeader header : wire) {
                record.addHeader(header.key(), header.value());
            }
            return producer.send(record).onComplete(ar -> {
                if (originRef == null) {
                    fireHooks(origin, topic, key, value, wire, operation, ar);
                } else {
                    fireHooks(origin, topic, key, value, wire, operation, originRef, ar);
                }
            });
        });
    }

    /**
     * Fires all registered {@link KafkaProducerCaptureHook} instances for a send that has no origin
     * reference. Every such send comes through this method; a send that has an origin reference
     * calls the other form directly and does not come through here. Delegates to
     * {@link #fireHooks(KafkaSendOrigin, String, String, byte[], KafkaRecordHeaders, KafkaProducerOperation, String, io.vertx.core.AsyncResult)}
     * with a {@code null} reference.
     *
     * @param origin         the send origin
     * @param topic          the target topic
     * @param key            the record key, or {@code null}
     * @param value          the serialized wire bytes
     * @param wire           the wire headers in order (application headers, then context headers)
     * @param operation      the producer operation, or {@code null}
     * @param ar             the settled send result
     */
    protected void fireHooks(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            byte[] value,
            KafkaRecordHeaders wire,
            @Nullable KafkaProducerOperation operation,
            io.vertx.core.AsyncResult<RecordMetadata> ar) {
        fireHooks(origin, topic, key, value, wire, operation, null, ar);
    }

    /**
     * Fires all registered {@link KafkaProducerCaptureHook} instances in sorted order, isolating
     * each hook in a {@code try/catch}. An {@link Exception}, {@link LinkageError},
     * {@link AssertionError} or {@link StackOverflowError} from one hook never propagates to callers
     * and does not stop the remaining hooks; it is reported through the
     * {@link ObserverFailureReporter}: a {@link LinkageError} at error level at a limited rate per
     * hook class, the others at warn level each time, by class name. Any other {@link Error}
     * propagates.
     *
     * @param origin         the send origin
     * @param topic          the target topic
     * @param key            the record key, or {@code null}
     * @param value          the serialized wire bytes
     * @param wire           the wire headers in order (application headers, then context headers)
     * @param operation      the producer operation, or {@code null}
     * @param originRef      the caller's reference to what the send carries (the outbox entry id),
     *                       or {@code null}
     * @param ar             the settled send result
     */
    protected void fireHooks(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            byte[] value,
            KafkaRecordHeaders wire,
            @Nullable KafkaProducerOperation operation,
            @Nullable String originRef,
            io.vertx.core.AsyncResult<RecordMetadata> ar) {
        if (captureHooks.isEmpty()) {
            return;
        }
        KafkaProducerSend send = new KafkaProducerSend(
                origin, topic, key, PayloadSources.buffered(value, null), wire, operation, ar, originRef);
        for (KafkaProducerCaptureHook hook : captureHooks) {
            try {
                hook.onSend(send);
            } catch (Exception | LinkageError | AssertionError | StackOverflowError ex) {
                hookFailureReporter.report(hook.getClass(), "onSend", ex);
            }
        }
    }

    /**
     * Returns the shared producer, creating it lazily on first call using a lock-free CAS.
     *
     * <p>All callers run on event loop threads, so races are extremely unlikely. In the rare
     * case of a race, the losing thread's producer is discarded (it will be garbage collected).
     * Only one producer is retained via {@link AtomicReference#compareAndSet}.
     *
     * @return a future of the shared Kafka producer
     */
    private Future<io.vertx.kafka.client.producer.KafkaProducer<String, byte[]>> getOrCreateProducer() {
        io.vertx.kafka.client.producer.KafkaProducer<String, byte[]> existing = shared.get();
        if (existing != null) {
            return Future.succeededFuture(existing);
        }
        Map<String, String> props = buildProducerProperties();
        if (log.isDebugEnabled()) {
            log.debug("Creating shared Kafka producer with properties: {}", maskSensitiveProperties(props));
        }
        io.vertx.kafka.client.producer.KafkaProducer<String, byte[]> created =
                io.vertx.kafka.client.producer.KafkaProducer.create(vertx, props, String.class, byte[].class);
        if (shared.compareAndSet(null, created)) {
            log.info("Created shared Kafka producer");
            return Future.succeededFuture(created);
        }
        // Another thread raced and won — close our instance and use theirs
        created.close();
        return Future.succeededFuture(shared.get());
    }

    /**
     * Builds the Kafka producer properties from the application configuration.
     *
     * <p>Merges in priority order (lowest to highest):
     * <ol>
     *   <li>{@link KafkaConfig#connectionProperties()} — the loose top-level connection keys
     *       ({@code bootstrap.servers}, {@code security.protocol}, {@code sasl.*}) in flat-dotted or
     *       nested form</li>
     *   <li>{@link KafkaConfig#properties()} — the global {@code kafka.properties} bag</li>
     *   <li>{@link KafkaConfig#producerProperties()} — the global producer bag
     *       {@code kafka.producer.properties}</li>
     * </ol>
     *
     * <p>If {@code security.protocol} contains "SASL" and {@code sasl.jaas.config} is absent
     * but {@code sasl.username} and {@code sasl.password} are present, the JAAS config string
     * is constructed automatically (with special-character escaping).
     *
     * @return a map of Kafka property keys to values
     */
    private Map<String, String> buildProducerProperties() {
        Map<String, String> props = new HashMap<>();

        // Layer 0: fixed serializers
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");

        // Layer 1: loose top-level Kafka connection keys (flat-dotted or nested, both flattened to dotted)
        KafkaConfigHelper.flattenToMap(kafkaConfig.connectionProperties()).forEach(props::put);

        // Layer 2: global kafka.properties
        JsonObject globalProps = kafkaConfig.properties() != null ? kafkaConfig.properties() : new JsonObject();
        KafkaConfigHelper.flattenToMap(globalProps).forEach(props::put);

        // Layer 3: global producer bag kafka.producer.properties
        KafkaConfigHelper.flattenToMap(kafkaConfig.producerProperties()).forEach(props::put);

        // Fail-fast: bootstrap.servers must be present and non-blank after all layers are merged
        String bootstrapServers = props.get("bootstrap.servers");
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("No bootstrap.servers configured for Kafka producer");
        }

        KafkaConfigHelper.autoConstructSaslJaasConfig(props);

        return props;
    }

    /**
     * Returns a copy of the properties map with sensitive values masked for safe logging.
     *
     * @param props the original properties map
     * @return a map suitable for debug logging
     */
    private static Map<String, String> maskSensitiveProperties(Map<String, String> props) {
        Map<String, String> masked = new HashMap<>(props);
        masked.replaceAll((k, v) -> isSensitiveKey(k) ? "***" : v);
        return masked;
    }

    /**
     * Returns {@code true} if the property key corresponds to a sensitive credential value.
     *
     * <p>Delegates to {@link KafkaSecretKeys#isSensitiveKey(String)} — the single definition of the
     * secret-key rule shared with the config-record scrubbing.
     *
     * @param key the Kafka property key
     * @return {@code true} for JAAS config, password, or secret-bearing keys
     */
    private static boolean isSensitiveKey(String key) {
        return KafkaSecretKeys.isSensitiveKey(key);
    }

    /**
     * Handles {@link Object} methods (equals, hashCode, toString) for the proxy.
     *
     * @param proxy the proxy instance
     * @param method the Object method being invoked
     * @param args the method arguments
     * @param iface the producer interface for toString representation
     * @return the result appropriate for the method
     * @throws UnsupportedOperationException for unrecognised Object methods
     */
    private static Object handleObjectMethod(Object proxy, Method method, Object[] args, Class<?> iface) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "KafkaProducerProxy[" + iface.getSimpleName() + "]";
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
        };
    }
}
