// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.util.TypeResolver;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaConsumerConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Scanning logic for all four Kafka consumption models, extracted from {@link KafkaConsumerRegistrar}.
 *
 * <p>This class is package-private and intended to be instantiated once per {@link KafkaConsumerRegistrar#scan}
 * invocation. Violations are accumulated across all three scan phases and reported together by the registrar.
 *
 * <p>{@link ServiceTargetResolver} is the source of truth for address resolution. When an operation
 * carries a stable target id (i.e., it is annotated with {@link dev.vertique.services.ServiceOperation}),
 * the resolver is used to derive the event bus address and the durable {@code stableTargetId}. For
 * operations without {@code @ServiceOperation} the address falls back to {@link ServiceMethodMeta#address()}.
 *
 * <p>The three high-level scan methods correspond to the four consumption models:
 * <ol>
 *   <li>{@link #scanKafkaSources} — Model 1: {@code @KafkaSource} on service implementation methods.</li>
 *   <li>{@link #processBindings} — Model 2: declarative {@link KafkaConsumerBinding} instances.</li>
 *   <li>{@link #scanListeners} — Models 3 and 4: {@link KafkaListener}-annotated classes and
 *       {@link KafkaRecordHandler} instances.</li>
 * </ol>
 */
@Slf4j
final class KafkaConsumerScanner {

    private final ServiceTargetResolver serviceTargetResolver;
    private final KafkaSerdeRegistry serdeRegistry;
    private final Map<String, KafkaConsumerConfig> consumerIndex;

    /**
     * Creates a scanner backed by the given target resolver, serde registry, and per-consumer config
     * index.
     *
     * @param serviceTargetResolver the resolver used to derive stable target ids and event bus
     *     addresses from service operation metadata; must not be {@code null}
     * @param serdeRegistry the serde registry used to resolve value formats and build deserializers;
     *     must not be {@code null}
     * @param consumerIndex the immutable {@code name -> KafkaConsumerConfig} index used to look up the
     *     typed per-consumer config by binding name; must not be {@code null}
     */
    KafkaConsumerScanner(
            ServiceTargetResolver serviceTargetResolver,
            KafkaSerdeRegistry serdeRegistry,
            Map<String, KafkaConsumerConfig> consumerIndex) {
        this.serviceTargetResolver = serviceTargetResolver;
        this.serdeRegistry = serdeRegistry;
        this.consumerIndex = consumerIndex;
    }

    // --- Model 1: @KafkaSource on service implementation methods ---

    /**
     * Scans all registered service implementations for methods annotated with {@link KafkaSource}.
     * Emits a violation if the annotation is placed on a contract interface method instead.
     *
     * <p>Binding name defaults to {@code "{type}-{serviceName}-{operationId}"} when a type is
     * present, or {@code "{serviceName}-{operationId}"} when absent. An explicit {@link KafkaSource#name()}
     * always takes precedence.
     *
     * @param serviceRegistry the service contract registry to iterate
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry
     *     / connection property bags; per-consumer config is looked up via the scanner's index)
     * @param entries mutable list to add new entries into
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     */
    void scanKafkaSources(
            ServiceContractRegistry serviceRegistry,
            KafkaConfig kafkaConfig,
            List<ConsumerEntry> entries,
            Set<String> usedNames,
            List<String> violations) {

        for (ContractEntry<?> contractEntry : serviceRegistry.entries()) {
            // Validate that @KafkaSource is not placed on contract interface methods
            for (Method ifaceMethod : contractEntry.contract().getMethods()) {
                if (ifaceMethod.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (ifaceMethod.isAnnotationPresent(KafkaSource.class)) {
                    violations.add("Place @KafkaSource on the implementation method, not the contract interface: "
                            + contractEntry.contract().getSimpleName() + "." + ifaceMethod.getName() + "()");
                }
            }

            // Scan implementation class methods — prefer generated companion when available
            Object impl = contractEntry.serviceInstance();
            Class<?> implClass = impl.getClass();

            List<KafkaBindingMeta> generatedMetas = GeneratedBindingMetaLoader.load(implClass);
            if (generatedMetas != null) {
                // Generated path: process only SOURCE-kind metas for @KafkaSource scanning.
                // A class that is both @KafkaListener and has @KafkaSource methods produces a
                // companion with mixed kinds; ROUTER/HANDLER metas must be ignored here so that
                // each scan path consumes only its own kind.
                for (KafkaBindingMeta meta : generatedMetas) {
                    if (meta.kind() != KafkaBindingMeta.Kind.SOURCE) {
                        continue;
                    }
                    ConsumerEntry e = GeneratedBindingMetaLoader.toSourceEntry(
                            meta,
                            contractEntry,
                            serviceTargetResolver,
                            serdeRegistry,
                            kafkaConfig,
                            consumerIndex,
                            usedNames,
                            violations);
                    if (e != null) {
                        entries.add(e);
                    }
                }
                continue; // skip reflective per-method scan for this impl class
            }

            // Reflective fallback: scan implementation methods for @KafkaSource
            for (Method implMethod : implClass.getDeclaredMethods()) {
                KafkaSource annotation = implMethod.getAnnotation(KafkaSource.class);
                if (annotation == null) {
                    continue;
                }

                // Find the corresponding operation metadata for the method
                ServiceMethodMeta meta = contractEntry.operations().get(implMethod.getName());
                if (meta == null) {
                    // Try to find by method name (operation may use @ServiceOperation alias)
                    for (ServiceMethodMeta m : contractEntry.operations().values()) {
                        if (m.method().name().equals(implMethod.getName())) {
                            meta = m;
                            break;
                        }
                    }
                }
                if (meta == null) {
                    violations.add("@KafkaSource on " + implClass.getSimpleName() + "." + implMethod.getName()
                            + "() does not correspond to any operation on contract "
                            + contractEntry.contract().getSimpleName());
                    continue;
                }

                // Resolve binding name: explicit > type-qualified > plain
                String bindingName = KafkaConsumerValidation.deriveBindingName(
                        annotation.name(), contractEntry.namespace(), contractEntry.name(), meta.operation());

                // Resolve address and stable target id via ServiceTargetResolver when eligible
                KafkaConsumerValidation.ResolvedTarget resolved =
                        KafkaConsumerValidation.resolveTarget(meta, contractEntry, serviceTargetResolver);
                String targetAddress = resolved.address();
                String stableTargetId = resolved.stableTargetId();

                log.info(
                        "Found @KafkaSource on {}.{}() → binding name='{}' stableTargetId={}",
                        implClass.getSimpleName(),
                        implMethod.getName(),
                        bindingName,
                        stableTargetId);

                ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                        bindingName,
                        annotation.topic(),
                        annotation.groupId(),
                        true,
                        annotation.commitStrategy(),
                        annotation.errorStrategy(),
                        annotation.deadLetterTopic(),
                        30_000L,
                        // @KafkaSource carries no value JSON profile; config still wins if present
                        null,
                        kafkaConfig,
                        consumerIndex.get(bindingName));

                ConsumerEntry entry = validateAndBuild(
                        bindingName,
                        config,
                        ConsumerEntry.Kind.BINDING,
                        meta.payloadType(),
                        targetAddress,
                        stableTargetId,
                        meta.oneWay(),
                        List.of(),
                        null,
                        null,
                        null,
                        usedNames,
                        violations);

                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
    }

    // --- Model 2: Declarative KafkaConsumerBinding ---

    /**
     * Processes declarative {@link KafkaConsumerBinding} instances contributed via Dagger multibinding.
     * Validates that the target service and operation exist in the registry.
     *
     * <p>Address resolution uses {@link ServiceTargetResolver#resolve(Class, String)} first (treating
     * {@link KafkaConsumerBinding#targetOperation()} as a durable operation id). If the resolver throws
     * (e.g., the operation was registered without {@link dev.vertique.services.ServiceOperation}),
     * resolution falls back to a direct registry lookup by operation name for backward compatibility.
     *
     * @param bindings the set of declarative bindings
     * @param serviceRegistry the service contract registry for target validation
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry
     *     / connection property bags; per-consumer config is looked up via the scanner's index)
     * @param entries mutable list to add new entries into
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     */
    void processBindings(
            Set<KafkaConsumerBinding<?>> bindings,
            ServiceContractRegistry serviceRegistry,
            KafkaConfig kafkaConfig,
            List<ConsumerEntry> entries,
            Set<String> usedNames,
            List<String> violations) {

        for (KafkaConsumerBinding<?> binding : bindings) {
            String name = binding.name();

            // Validate target service exists
            ContractEntry<?> contractEntry;
            try {
                contractEntry = serviceRegistry.resolve(binding.targetService());
            } catch (IllegalArgumentException e) {
                violations.add("KafkaConsumerBinding '" + name + "': target service "
                        + binding.targetService().getSimpleName() + " is not registered");
                continue;
            }

            // Resolve address and stable target id: try resolver first, fall back to registry lookup
            String targetAddress;
            String stableTargetId;
            ServiceMethodMeta meta;
            try {
                ResolvedServiceTarget resolved =
                        serviceTargetResolver.resolve(binding.targetService(), binding.targetOperation());
                targetAddress = resolved.address();
                stableTargetId = resolved.targetId();
                meta = resolved.meta();
            } catch (IllegalArgumentException resolverEx) {
                // Fall back: operation registered without @ServiceOperation (method-name-based)
                meta = contractEntry.operations().get(binding.targetOperation());
                if (meta == null) {
                    violations.add("KafkaConsumerBinding '" + name + "': operation '"
                            + binding.targetOperation() + "' not found on "
                            + binding.targetService().getSimpleName());
                    continue;
                }
                targetAddress = meta.address();
                stableTargetId = null;
            }

            // Validate payload type compatibility
            if (meta.payloadType() != null
                    && binding.valueType() != null
                    && !meta.payloadType().isAssignableFrom(binding.valueType())) {
                violations.add("KafkaConsumerBinding '" + name + "': value type "
                        + binding.valueType().getSimpleName() + " is not assignable to operation payload type "
                        + meta.payloadType().getSimpleName());
                continue;
            }

            log.info(
                    "Processing KafkaConsumerBinding '{}' → {}.{} stableTargetId={}",
                    name,
                    binding.targetService().getSimpleName(),
                    binding.targetOperation(),
                    stableTargetId);

            ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                    name,
                    binding.topic(),
                    binding.groupId(),
                    binding.enabled(),
                    binding.commitStrategy(),
                    binding.errorStrategy(),
                    binding.deadLetterTopic(),
                    binding.eventBusTimeoutMs(),
                    binding.jsonProfile() != null ? binding.jsonProfile().value() : null,
                    kafkaConfig,
                    consumerIndex.get(name));

            ConsumerEntry entry = validateAndBuild(
                    name,
                    config,
                    ConsumerEntry.Kind.BINDING,
                    binding.valueType(),
                    targetAddress,
                    stableTargetId,
                    meta.oneWay(),
                    List.of(),
                    null,
                    binding.deserializer(),
                    binding.filter(),
                    usedNames,
                    violations);

            if (entry != null) {
                entries.add(entry);
            }
        }
    }

    // --- Models 3 and 4: @KafkaListener handlers ---

    /**
     * Scans the {@link KafkaConsumers} handler set for {@code Class<?>} routing interfaces
     * (Model 3) and {@link KafkaRecordHandler} instances (Model 4).
     *
     * @param handlers the contributed handler objects (classes or handler instances)
     * @param serviceRegistry the service contract registry for dispatch target validation
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry
     *     / connection property bags; per-consumer config is looked up via the scanner's index)
     * @param entries mutable list to add new entries into
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     */
    void scanListeners(
            Set<Object> handlers,
            ServiceContractRegistry serviceRegistry,
            KafkaConfig kafkaConfig,
            List<ConsumerEntry> entries,
            Set<String> usedNames,
            List<String> violations) {

        for (Object contributed : handlers) {
            Class<?> consumerClass = (contributed instanceof Class<?> c) ? c : contributed.getClass();

            List<KafkaBindingMeta> generatedMetas = GeneratedBindingMetaLoader.load(consumerClass);
            if (generatedMetas != null) {
                // Generated path: filter by kind so each scan path consumes only its own kind.
                // A companion with mixed kinds (dual-annotation class) must not feed a ROUTER meta
                // to toHandlerEntry or a HANDLER meta to toRouterEntry.
                for (KafkaBindingMeta meta : generatedMetas) {
                    ConsumerEntry e;
                    if (contributed instanceof Class<?>) {
                        // Model 3: routing interface — process only ROUTER metas
                        if (meta.kind() != KafkaBindingMeta.Kind.ROUTER) {
                            continue;
                        }
                        e = GeneratedBindingMetaLoader.toRouterEntry(
                                meta,
                                serviceRegistry,
                                serviceTargetResolver,
                                serdeRegistry,
                                kafkaConfig,
                                consumerIndex,
                                usedNames,
                                violations);
                    } else {
                        // Model 4: KafkaRecordHandler instance — process only HANDLER metas
                        if (meta.kind() != KafkaBindingMeta.Kind.HANDLER) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        KafkaRecordHandler<Object> handler = (KafkaRecordHandler<Object>) contributed;
                        e = GeneratedBindingMetaLoader.toHandlerEntry(
                                meta, handler, serdeRegistry, kafkaConfig, consumerIndex, usedNames, violations);
                    }
                    if (e != null) {
                        entries.add(e);
                    }
                }
                continue; // skip reflective scan for this contribution
            }

            // Reflective fallback
            if (contributed instanceof Class<?> cls) {
                // Model 3: routing interface
                processRouterClass(cls, serviceRegistry, kafkaConfig, entries, usedNames, violations);
            } else if (contributed instanceof KafkaRecordHandler<?> handler) {
                // Model 4: custom handler instance
                processHandlerInstance(handler, kafkaConfig, entries, usedNames, violations);
            } else {
                violations.add("@KafkaConsumers contribution is not a Class<?> or KafkaRecordHandler: "
                        + contributed.getClass().getName());
            }
        }
    }

    /**
     * Processes a {@link KafkaListener}-annotated routing interface (Model 3) by scanning
     * its {@link KafkaHandler}-annotated methods and building route entries.
     *
     * <p>For each {@link DispatchTo}-annotated handler method, address resolution uses
     * {@link ServiceTargetResolver#resolve(Class, String)} first. If the resolver throws
     * (operation registered without {@link dev.vertique.services.ServiceOperation}), resolution
     * falls back to a direct registry lookup by operation name for backward compatibility.
     *
     * @param cls the routing interface class (must have {@link KafkaListener})
     * @param serviceRegistry the service contract registry for dispatch target validation
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry
     *     / connection property bags; per-consumer config is looked up via the scanner's index)
     * @param entries mutable list to add new entries into
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     */
    private void processRouterClass(
            Class<?> cls,
            ServiceContractRegistry serviceRegistry,
            KafkaConfig kafkaConfig,
            List<ConsumerEntry> entries,
            Set<String> usedNames,
            List<String> violations) {

        KafkaListener listener = cls.getAnnotation(KafkaListener.class);
        if (listener == null) {
            violations.add("Contributed Class<?> " + cls.getName() + " is missing @KafkaListener annotation");
            return;
        }

        String name = listener.name();
        log.info("Scanning @KafkaListener routing interface '{}' on {}", name, cls.getSimpleName());

        // Read the type-level @JsonProfile annotation as the sole per-binding profile selector, and
        // reject any method-level @JsonProfile placement (FR-JSON-066). An illegal placement records a
        // violation and aborts this listener so the registrar fails fast at boot
        // (KafkaRegistrationException / ConfigurationException).
        int violationsBefore = violations.size();
        rejectMethodLevelJsonProfile(cls, name, violations);
        String jsonProfile = resolveListenerJsonProfile(cls);
        if (violations.size() != violationsBefore) {
            return;
        }

        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                name,
                listener.topic(),
                listener.groupId(),
                true,
                listener.commitStrategy(),
                listener.errorStrategy(),
                listener.deadLetterTopic(),
                30_000L,
                jsonProfile,
                kafkaConfig,
                consumerIndex.get(name));

        List<ConsumerEntry.RouteEntry> routes = new ArrayList<>();
        int defaultHandlerCount = 0;
        Map<String, String> methodErrors = new HashMap<>();

        for (Method method : cls.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            KafkaHandler handlerAnnotation = method.getAnnotation(KafkaHandler.class);
            if (handlerAnnotation == null) {
                continue;
            }

            // Validate mutual exclusivity of match conditions
            int matchCount = 0;
            if (!handlerAnnotation.matchHeader().isBlank()) matchCount++;
            if (!handlerAnnotation.matchProperty().isBlank()) matchCount++;
            if (handlerAnnotation.defaultHandler()) matchCount++;

            if (matchCount == 0) {
                methodErrors.put(
                        method.getName(),
                        "Method " + method.getName() + " must specify exactly one of matchHeader, "
                                + "matchProperty, or defaultHandler=true");
                continue;
            }
            if (matchCount > 1) {
                methodErrors.put(
                        method.getName(),
                        "Method " + method.getName() + " specifies multiple match conditions — "
                                + "use exactly one of matchHeader, matchProperty, or defaultHandler");
                continue;
            }

            if (handlerAnnotation.defaultHandler()) {
                defaultHandlerCount++;
                if (defaultHandlerCount > 1) {
                    methodErrors.put(
                            method.getName(),
                            "At most one defaultHandler=true method per @KafkaListener, found multiple in "
                                    + cls.getSimpleName());
                }
            }

            // Resolve dispatch target
            DispatchTo dispatchTo = method.getAnnotation(DispatchTo.class);
            String targetAddress = null;
            String routeStableTargetId = null;
            boolean targetOneWay = false;
            Class<?> valueType = resolveRouteValueType(method);

            if (dispatchTo != null) {
                // Validate service is registered
                ContractEntry<?> targetEntry;
                try {
                    targetEntry = serviceRegistry.resolve(dispatchTo.service());
                } catch (IllegalArgumentException e) {
                    methodErrors.put(
                            method.getName(),
                            "Router method " + method.getName() + " @DispatchTo target service "
                                    + dispatchTo.service().getSimpleName() + " is not registered");
                    continue;
                }

                // Resolve address via ServiceTargetResolver, with fallback to registry lookup
                try {
                    ResolvedServiceTarget resolved =
                            serviceTargetResolver.resolve(dispatchTo.service(), dispatchTo.operation());
                    targetAddress = resolved.address();
                    routeStableTargetId = resolved.targetId();
                    targetOneWay = resolved.meta().oneWay();
                } catch (IllegalArgumentException resolverEx) {
                    // Fall back: operation registered without @ServiceOperation (method-name-based)
                    ServiceMethodMeta opMeta = targetEntry.operations().get(dispatchTo.operation());
                    if (opMeta == null) {
                        methodErrors.put(
                                method.getName(),
                                "Router method " + method.getName() + " @DispatchTo operation '"
                                        + dispatchTo.operation() + "' not found on "
                                        + dispatchTo.service().getSimpleName());
                        continue;
                    }
                    targetAddress = opMeta.address();
                    routeStableTargetId = null;
                    targetOneWay = opMeta.oneWay();
                }
            }

            routes.add(new ConsumerEntry.RouteEntry(
                    handlerAnnotation.matchHeader(),
                    handlerAnnotation.matchProperty(),
                    handlerAnnotation.matchValue(),
                    handlerAnnotation.defaultHandler(),
                    valueType != null ? valueType : Void.class,
                    targetAddress,
                    routeStableTargetId,
                    targetOneWay));
        }

        for (String msg : methodErrors.values()) {
            violations.add("[" + name + "] " + msg);
        }

        ConsumerEntry entry = validateAndBuild(
                name,
                config,
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                routes,
                null,
                null,
                null,
                usedNames,
                violations);

        if (entry != null) {
            entries.add(entry);
        }
    }

    /**
     * Resolves the value type for a router method from its first non-injectable parameter type.
     *
     * @param method the {@link KafkaHandler}-annotated method
     * @return the parameter type, or {@code null} if no parameters are declared
     */
    private Class<?> resolveRouteValueType(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return null;
        }
        return params[0];
    }

    /**
     * Processes a {@link KafkaRecordHandler} instance (Model 4) by resolving the generic type
     * argument {@code V} and building a handler entry.
     *
     * @param handler the handler instance
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry
     *     / connection property bags; per-consumer config is looked up via the scanner's index)
     * @param entries mutable list to add new entries into
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     */
    private void processHandlerInstance(
            KafkaRecordHandler<?> handler,
            KafkaConfig kafkaConfig,
            List<ConsumerEntry> entries,
            Set<String> usedNames,
            List<String> violations) {

        Class<?> handlerClass = handler.getClass();
        KafkaListener listener = handlerClass.getAnnotation(KafkaListener.class);
        if (listener == null) {
            violations.add("KafkaRecordHandler " + handlerClass.getName() + " is missing @KafkaListener annotation");
            return;
        }

        String name = listener.name();
        log.info("Scanning @KafkaListener handler '{}' on {}", name, handlerClass.getSimpleName());

        // Resolve the generic type parameter V from KafkaRecordHandler<V>
        Class<?> valueType = TypeResolver.resolveTypeArgument(handlerClass, KafkaRecordHandler.class);
        if (valueType == null) {
            violations.add("[" + name + "] Cannot resolve type argument V of KafkaRecordHandler on "
                    + handlerClass.getName() + " (raw type or unresolved type variable)");
            return;
        }

        // Validate that valueType matches @KafkaListener.valueType() if explicitly set
        Class<?> annotatedValueType = listener.valueType();
        if (annotatedValueType != Void.class && !annotatedValueType.isAssignableFrom(valueType)) {
            violations.add("[" + name + "] @KafkaListener.valueType() " + annotatedValueType.getSimpleName()
                    + " is not assignable from resolved handler type " + valueType.getSimpleName());
            return;
        }

        // Read the type-level @JsonProfile annotation as the sole per-binding profile selector, and
        // reject any method-level @JsonProfile placement (FR-JSON-066). An illegal placement records a
        // violation and aborts this listener so the registrar fails fast at boot
        // (KafkaRegistrationException / ConfigurationException).
        int violationsBefore = violations.size();
        rejectMethodLevelJsonProfile(handlerClass, name, violations);
        String jsonProfile = resolveListenerJsonProfile(handlerClass);
        if (violations.size() != violationsBefore) {
            return;
        }

        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                name,
                listener.topic(),
                listener.groupId(),
                true,
                listener.commitStrategy(),
                listener.errorStrategy(),
                listener.deadLetterTopic(),
                30_000L,
                jsonProfile,
                kafkaConfig,
                consumerIndex.get(name));

        ConsumerEntry entry = validateAndBuild(
                name,
                config,
                ConsumerEntry.Kind.HANDLER,
                valueType,
                null,
                null,
                false,
                List.of(),
                handler,
                null,
                null,
                usedNames,
                violations);

        if (entry != null) {
            entries.add(entry);
        }
    }

    // --- @JsonProfile resolution (FR-JSON-066) ---

    /**
     * Resolves the effective JSON mapper profile for a reflectively-scanned {@code @KafkaListener}
     * type from the type-level {@link JsonProfile} annotation. {@code @JsonProfile} is the sole
     * per-binding profile selector: a non-blank value selects that profile, and a blank or absent
     * annotation resolves to {@code ""} (the framework default).
     *
     * <p>This mirrors the codegen lane ({@code KafkaListenerScanner.resolveEffectiveProfile}) so the
     * generated and reflective consumer lanes never diverge.
     *
     * @param cls the reflectively-scanned {@code @KafkaListener} type; must not be {@code null}
     * @return the effective profile id, or {@code ""} for the framework default
     */
    private String resolveListenerJsonProfile(Class<?> cls) {
        JsonProfile annotation = cls.getAnnotation(JsonProfile.class);
        if (annotation == null || annotation.value().isBlank()) {
            return "";
        }
        return annotation.value();
    }

    /**
     * Rejects a method-level {@link JsonProfile} on a reflectively-scanned {@code @KafkaListener} type
     * (FR-JSON-066). {@code @JsonProfile} selects a profile for the whole listener and must be placed
     * at TYPE level; a method-level placement on either a router interface or a direct-handler class
     * records a violation naming the offending method, aborting boot via the registrar.
     *
     * <p>This mirrors the codegen lane ({@code KafkaListenerScanner.rejectMethodLevelJsonProfile}) so
     * the generated and reflective consumer lanes apply identical placement rules.
     *
     * <p>Scans the inherited-inclusive member set via {@link Class#getMethods()} — the same source
     * {@link #processRouterClass} uses to read handler methods — so a method-level {@code @JsonProfile}
     * on an <em>inherited</em> {@code @KafkaHandler} method (declared on a super-type) is also rejected,
     * not silently bypassed. Methods declared on {@link Object} are skipped.
     *
     * @param cls the reflectively-scanned {@code @KafkaListener} type; must not be {@code null}
     * @param name the binding name, used to scope violation messages
     * @param violations mutable list to collect violation messages
     */
    private void rejectMethodLevelJsonProfile(Class<?> cls, String name, List<String> violations) {
        for (Method method : cls.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(JsonProfile.class) != null) {
                violations.add("[" + name + "] @JsonProfile on method " + method.getName() + " in "
                        + cls.getSimpleName()
                        + " is not allowed; place @JsonProfile at TYPE level on the @KafkaListener type");
            }
        }
    }

    // --- Common validation and construction ---

    /**
     * Delegates to {@link KafkaConsumerValidation#validateAndBuild} for shared validation logic.
     *
     * @param name the binding name
     * @param config the resolved consumer config
     * @param kind the consumption kind
     * @param valueType the deserialization target type
     * @param targetAddress the event bus dispatch address
     * @param stableTargetId the durable stable target id; {@code null} if not available
     * @param targetOneWay whether the dispatch is fire-and-forget
     * @param routes route entries for ROUTER kind
     * @param handler the handler instance for HANDLER kind
     * @param customDeserializer the Model-2 custom deserializer, or {@code null} to select via the registry
     * @param filter the pre-deserialization filter, or {@code null} for accept-all
     * @param usedNames mutable set of claimed names (updated if name is new)
     * @param violations mutable list to collect violation messages
     * @return the built entry, or {@code null} if any per-entry violation was recorded
     */
    private ConsumerEntry validateAndBuild(
            String name,
            ResolvedKafkaConsumerConfig config,
            ConsumerEntry.Kind kind,
            Class<?> valueType,
            String targetAddress,
            String stableTargetId,
            boolean targetOneWay,
            List<ConsumerEntry.RouteEntry> routes,
            KafkaRecordHandler<?> handler,
            KafkaDeserializer<?> customDeserializer,
            KafkaRecordFilter filter,
            Set<String> usedNames,
            List<String> violations) {

        return KafkaConsumerValidation.validateAndBuild(
                name,
                config,
                kind,
                valueType,
                targetAddress,
                stableTargetId,
                targetOneWay,
                routes,
                handler,
                serdeRegistry,
                customDeserializer,
                filter,
                usedNames,
                violations);
    }
}
