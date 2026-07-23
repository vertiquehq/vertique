// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.core.util.Strings;
import dev.vertique.kafka.KafkaConfigHelper;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Before constructing each {@link KafkaProducerRecord}, currently bound context values are
 * captured via {@link DurableContextPropagator#mergeCaptured} and merged into the record
 * headers (FR-CTX-173).
 *
 * <p>Every send converges at {@link #sendWire(String, String, byte[], Map, KafkaSendOrigin, Method)},
 * which fires all registered {@link KafkaProducerCaptureHook} instances (observer-only) after the
 * underlying send settles. Hooks are sorted by {@link OrderedExtension#comparator()} and isolated
 * via try/catch so a throwing hook never affects the send result.
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
     * <p>Parameter resolution per method (position-based):
     * <ol>
     *   <li>One parameter — value only (key is {@code null})</li>
     *   <li>Two parameters, first is {@code String} — key + value</li>
     *   <li>Two parameters, second is {@code Map} — value + headers</li>
     *   <li>Three parameters, first is {@code String}, third is {@code Map} — key + value + headers</li>
     * </ol>
     *
     * @param <T> the producer interface type
     * @param producerInterface the interface class annotated with {@link KafkaProducer}
     * @return a proxy instance implementing {@code producerInterface}
     * @throws IllegalArgumentException if the interface is not annotated with {@link KafkaProducer}
     *     or a method is missing a {@link Topic} annotation
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> producerInterface) {
        KafkaProducer annotation = producerInterface.getAnnotation(KafkaProducer.class);
        if (annotation == null) {
            throw new IllegalArgumentException(producerInterface.getName() + " is not annotated with @KafkaProducer");
        }
        String producerName = annotation.name().isEmpty() ? producerInterface.getSimpleName() : annotation.name();

        KafkaProducerConfig producerConfig = producerIndex.get(producerName);

        Map<String, String> methodTopics = resolveMethodTopics(producerInterface, producerConfig);
        Map<String, KafkaSerializer<Object>> methodSerializers =
                resolveMethodSerializers(producerInterface, producerName, producerConfig, kafkaConfig, serdeRegistry);
        builtSerializers.addAll(methodSerializers.values());

        return (T) Proxy.newProxyInstance(
                producerInterface.getClassLoader(), new Class<?>[] {producerInterface}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args, producerInterface);
                    }
                    String topic = methodTopics.get(method.getName());
                    String key = null;
                    Object value = null;
                    Map<String, String> headers = null;

                    Class<?>[] paramTypes = method.getParameterTypes();
                    if (paramTypes.length == 1) {
                        value = args[0];
                    } else if (paramTypes.length >= 2 && paramTypes[0] == String.class) {
                        key = (String) args[0];
                        value = args[1];
                        if (paramTypes.length == 3 && Map.class.isAssignableFrom(paramTypes[2])) {
                            headers = (Map<String, String>) args[2];
                        }
                    } else if (paramTypes.length == 2 && Map.class.isAssignableFrom(paramTypes[1])) {
                        value = args[0];
                        headers = (Map<String, String>) args[1];
                    }

                    KafkaSerializer<Object> serializer = methodSerializers.get(method.getName());
                    // Event-loop safety (NFR-AVRO-005): a mayBlock serializer (e.g. Avro on a registry
                    // cache miss) is offloaded to the worker pool; non-blocking formats stay on-loop.
                    // Either way a serialization failure becomes a failed Future, never a synchronous
                    // throw out of the proxy (NFR-AVRO-003): executeBlocking captures the throw, and the
                    // on-loop branch is wrapped below.
                    if (serializer.mayBlock()) {
                        final String fTopic = topic;
                        final String fKey = key;
                        final Object fValue = value;
                        final Map<String, String> fHeaders = headers;
                        final Method fMethod = method;
                        return vertx.executeBlocking(() -> serializer.serialize(fValue, fTopic, fHeaders), false)
                                .compose(bytes -> sendRaw(
                                        fTopic, fKey, bytes, fHeaders, KafkaSendOrigin.DIRECT_PRODUCER, fMethod));
                    }
                    try {
                        byte[] bytes = serializer.serialize(value, topic, headers);
                        return sendRaw(topic, key, bytes, headers, KafkaSendOrigin.DIRECT_PRODUCER, method);
                    } catch (RuntimeException e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    // --- Public send API ---

    /**
     * Sends a raw byte array message to the specified topic, capturing ambient context from the
     * current {@link ContextHolder} scope into Kafka headers via
     * {@link DurableMetadataHeaderCodec#mergeForEgress}. Used internally for DLQ publishing and
     * direct send callers that want ambient context propagated automatically.
     *
     * <p>This overload uses origin {@link KafkaSendOrigin#INTERNAL}. Prefer the origin-explicit
     * overloads for callers that know their send origin.
     *
     * @param topic   the target topic
     * @param key     the message key, or {@code null}
     * @param value   the raw message bytes
     * @param headers the application message headers, or {@code null}; must not use the
     *                {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} prefix
     * @return a future of the record metadata
     * @throws IllegalArgumentException if any application header uses the reserved framework prefix
     */
    public Future<RecordMetadata> send(String topic, String key, byte[] value, Map<String, String> headers) {
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
     * {@link #sendForOutbox(String, String, byte[], Map, DurableMetadata)} when the caller is the
     * outbox relay.
     *
     * <p>The supplied {@code context} is projected to reserved headers via
     * {@link DurableMetadataHeaderCodec#mergeForEgress}, enforcing that no application header uses
     * the {@link DurableMetadataHeaderCodec#RESERVED_PREFIX}.
     *
     * @param topic    the target topic
     * @param key      the message key, or {@code null}
     * @param value    the raw message bytes
     * @param headers  the application message headers, or {@code null}; must not use the reserved
     *                 framework prefix
     * @param context  the explicit durable context to project into headers; must not be {@code null}
     * @return a future of the record metadata
     * @throws IllegalArgumentException if any application header uses the reserved framework prefix
     */
    public Future<RecordMetadata> send(
            String topic, String key, byte[] value, Map<String, String> headers, DurableMetadata context) {
        return sendWithContext(topic, key, value, headers, context, KafkaSendOrigin.INTERNAL, null);
    }

    /**
     * Sends a raw byte array message to the DLQ topic, capturing ambient context from the
     * current scope into Kafka headers. Fires capture hooks with origin
     * {@link KafkaSendOrigin#DLQ} and a {@code null} producer method.
     *
     * <p>Use this overload from {@link dev.vertique.kafka.KafkaErrorHandler} to ensure the
     * send is correctly classified as a dead-letter publish.
     *
     * @param topic   the dead-letter topic
     * @param key     the record key, or {@code null}
     * @param value   the raw record bytes to republish
     * @param headers the application message headers, or {@code null}
     * @return a future of the record metadata
     */
    public Future<RecordMetadata> sendForDlq(String topic, String key, byte[] value, Map<String, String> headers) {
        return sendRaw(topic, key, value, headers, KafkaSendOrigin.DLQ, null);
    }

    /**
     * Sends a raw byte array message from the outbox relay using an explicitly supplied
     * {@link DurableMetadata} context. Fires capture hooks with origin
     * {@link KafkaSendOrigin#OUTBOX} and a {@code null} producer method.
     *
     * <p>Use this overload from the outbox destination handler so the record's persisted durable
     * context (not the relay poller's ambient context) crosses the wire boundary.
     *
     * @param topic   the target Kafka topic
     * @param key     the record key, or {@code null}
     * @param value   the raw message bytes
     * @param headers the application message headers, or {@code null}
     * @param context the durable context stored in the outbox entry; must not be {@code null}
     * @return a future of the record metadata
     */
    public Future<RecordMetadata> sendForOutbox(
            String topic, String key, byte[] value, Map<String, String> headers, DurableMetadata context) {
        return sendWithContext(topic, key, value, headers, context, KafkaSendOrigin.OUTBOX, null);
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
     * Resolves the declared value parameter type for a producer method, mirroring the position-based
     * parameter resolution in {@link #create(Class)}.
     *
     * @param method the producer method
     * @return the value parameter type
     */
    static Class<?> resolveValueType(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 1) {
            return paramTypes[0];
        }
        if (paramTypes.length >= 2 && paramTypes[0] == String.class) {
            return paramTypes[1];
        }
        if (paramTypes.length == 2 && Map.class.isAssignableFrom(paramTypes[1])) {
            return paramTypes[0];
        }
        return paramTypes.length > 0 ? paramTypes[0] : Object.class;
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
     * {@link DurableContextPropagator#capture(String)} and merging it with the caller-supplied
     * headers via {@link DurableMetadataHeaderCodec#mergeForEgress} (FR-CTX-173).
     *
     * <p>This enforces that no application header uses the
     * {@link DurableMetadataHeaderCodec#RESERVED_PREFIX} ({@code "vertique-"}) prefix, and then
     * overlays the projected context headers on top.
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param headers        optional caller-supplied application headers; may be {@code null}
     * @param origin         the send origin to thread through to the wire funnel
     * @param producerMethod the proxy method, or {@code null} for non-proxy origins
     * @return a future of the record metadata; FAILS with {@link IllegalArgumentException} if any
     *         application header uses the reserved framework prefix (the merge never throws
     *         synchronously, so callers can classify the failure in {@code recover})
     */
    private Future<RecordMetadata> sendRaw(
            String topic,
            String key,
            byte[] value,
            Map<String, String> headers,
            KafkaSendOrigin origin,
            @Nullable Method producerMethod) {
        DurableMetadata ctx = propagator.capture(DispatchBoundary.KAFKA);
        final Map<String, String> wire;
        try {
            wire = DurableMetadataHeaderCodec.mergeForEgress(headers, ctx);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        return sendWire(topic, key, value, wire, origin, producerMethod);
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
     * @param producerMethod the proxy method, or {@code null} for non-proxy origins
     * @return a future of the record metadata; FAILS with {@link IllegalArgumentException} if any
     *         application header uses the reserved framework prefix (never throws synchronously)
     */
    private Future<RecordMetadata> sendWithContext(
            String topic,
            String key,
            byte[] value,
            Map<String, String> headers,
            DurableMetadata context,
            KafkaSendOrigin origin,
            @Nullable Method producerMethod) {
        final Map<String, String> wire;
        try {
            wire = DurableMetadataHeaderCodec.mergeForEgress(headers, context);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        return sendWire(topic, key, value, wire, origin, producerMethod);
    }

    /**
     * Creates the {@link KafkaProducerRecord}, adds all merged headers, sends it via the shared
     * producer, and then fires all registered {@link KafkaProducerCaptureHook} instances after the
     * send settles.
     *
     * <p>Hook invocation is observer-only: hooks are called after the result is determined and
     * each hook is isolated in a {@code try/catch} so a throwing hook never changes the result.
     *
     * @param topic          the target topic
     * @param key            the message key, or {@code null}
     * @param value          the serialized message bytes
     * @param wire           the fully merged wire headers (application + context); must not be {@code null}
     * @param origin         the send origin, threaded from the public entry point
     * @param producerMethod the proxy method for {@link KafkaSendOrigin#DIRECT_PRODUCER}, or
     *                       {@code null} for all other origins
     * @return a future of the record metadata; the future reflects the actual Kafka send result —
     *         hook exceptions do not change its outcome
     */
    protected Future<RecordMetadata> sendWire(
            String topic,
            String key,
            byte[] value,
            Map<String, String> wire,
            KafkaSendOrigin origin,
            @Nullable Method producerMethod) {
        return getOrCreateProducer().compose(producer -> {
            KafkaProducerRecord<String, byte[]> record = KafkaProducerRecord.create(topic, key, value);
            wire.forEach((k, v) -> record.addHeader(k, v));
            return producer.send(record)
                    .onComplete(ar -> fireHooks(origin, topic, key, value, wire, producerMethod, ar));
        });
    }

    /**
     * Fires all registered {@link KafkaProducerCaptureHook} instances in sorted order, isolating
     * each hook in a {@code try/catch} so exceptions never propagate to callers.
     *
     * @param origin         the send origin
     * @param topic          the target topic
     * @param key            the record key, or {@code null}
     * @param value          the serialized wire bytes
     * @param wire           the fully merged wire headers
     * @param producerMethod the proxy method, or {@code null}
     * @param ar             the settled send result
     */
    protected void fireHooks(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            byte[] value,
            Map<String, String> wire,
            @Nullable Method producerMethod,
            io.vertx.core.AsyncResult<RecordMetadata> ar) {
        if (captureHooks.isEmpty()) {
            return;
        }
        dev.vertique.core.payload.PayloadSource payloadSource = PayloadSources.buffered(value, null);
        for (KafkaProducerCaptureHook hook : captureHooks) {
            try {
                hook.onSend(origin, topic, key, payloadSource, wire, producerMethod, ar);
            } catch (Exception ex) {
                log.warn(
                        "[KafkaProducerFactory] Capture hook {} threw an exception — swallowing: {}",
                        hook.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex);
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
