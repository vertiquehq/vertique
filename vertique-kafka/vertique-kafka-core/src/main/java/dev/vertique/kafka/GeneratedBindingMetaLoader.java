// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.util.GeneratedNames;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaConsumerConfig;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads precomputed {@link KafkaBindingMeta} from a generated companion class and converts
 * each meta entry into a {@link ConsumerEntry} using the same resolution logic as
 * {@link KafkaConsumerScanner}.
 *
 * <p>The generated companion class is located via
 * {@link GeneratedNames#companionFqn(Class, String)} with the suffix {@code "_BindingMeta"}.
 * It must expose a {@code public static final List<KafkaBindingMeta> METAS} field.
 *
 * <p>The three conversion methods correspond to the three supported scan paths in the reflective
 * scanner:
 * <ul>
 *   <li>{@link #toSourceEntry} — Model 1 ({@code @KafkaSource} on service implementation methods)</li>
 *   <li>{@link #toRouterEntry} — Model 3 ({@code @KafkaListener} routing interface)</li>
 *   <li>{@link #toHandlerEntry} — Model 4 ({@code KafkaRecordHandler} instance)</li>
 * </ul>
 *
 * <p>All conversion methods append to the caller-supplied {@code usedNames} and {@code violations}
 * lists exactly as the reflective scanner does, enabling uniform cross-source validation.
 */
final class GeneratedBindingMetaLoader {

    /** Suffix appended to the origin class name to form the generated companion FQN. */
    private static final String COMPANION_SUFFIX = "_BindingMeta";

    /** Name of the static field on the generated companion that holds the metadata list. */
    private static final String METAS_FIELD = "METAS";

    private GeneratedBindingMetaLoader() {}

    // --- Companion Class Loading ---

    /**
     * Loads the precomputed {@link KafkaBindingMeta} list from the generated companion class for
     * the given consumer class, or returns {@code null} if no companion exists (reflective
     * scanning should be used instead).
     *
     * <p>The companion class is located at
     * {@link GeneratedNames#companionFqn(Class, String)} with suffix {@code "_BindingMeta"}.
     * If the companion class is present but malformed (missing {@code METAS} field, field is not
     * {@code static}, wrong element type, or inaccessible), a {@link KafkaRegistrationException}
     * is thrown with a clear diagnostic message — the loader never silently falls back to
     * reflective scanning when a companion is present but broken.
     *
     * @param consumerClass the origin consumer class whose companion is to be loaded
     * @return the list of {@link KafkaBindingMeta} from the companion, or {@code null} if the
     *     companion does not exist
     * @throws KafkaRegistrationException if the companion class is present but malformed
     */
    @Nullable
    static List<KafkaBindingMeta> load(Class<?> consumerClass) {
        String companionFqn = GeneratedNames.companionFqn(consumerClass, COMPANION_SUFFIX);
        Class<?> companionClass;
        try {
            companionClass = Class.forName(companionFqn, true, consumerClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            // No generated companion — caller falls back to reflective scanning
            return null;
        } catch (LinkageError broken) {
            // Companion is PRESENT but fails to link/initialize (failing static initializer or a missing
            // transitive dependency) — fail loudly rather than silently degrading to reflective scanning.
            // Preserve the LinkageError as the cause so its type/stack/root survive (parity with the other
            // companion-load sites).
            throw new KafkaRegistrationException(
                    List.of("Generated companion " + companionFqn + " is present but failed to load"), broken);
        }

        // Companion exists; it must be well-formed
        Field metasField;
        try {
            metasField = companionClass.getField(METAS_FIELD);
        } catch (NoSuchFieldException e) {
            throw new KafkaRegistrationException(
                    List.of("Generated companion " + companionFqn + " is missing the public static field '"
                            + METAS_FIELD + "' — regenerate sources with the current codegen processor"));
        }

        // Verify the field is static before reading it — a non-static METAS field would cause
        // metasField.get(null) to throw NullPointerException/IllegalArgumentException rather than
        // the intended loud KafkaRegistrationException.
        if (!Modifier.isStatic(metasField.getModifiers())) {
            throw new KafkaRegistrationException(
                    List.of("Generated companion " + companionFqn + " field '" + METAS_FIELD
                            + "' is malformed (must be a static List<KafkaBindingMeta>)"
                            + " — regenerate sources with the current codegen processor"));
        }

        Object value;
        try {
            value = metasField.get(null);
        } catch (IllegalAccessException e) {
            throw new KafkaRegistrationException(List.of("Generated companion " + companionFqn + " field '"
                    + METAS_FIELD + "' is not accessible — it must be public static final"));
        }

        if (!(value instanceof List<?> list)) {
            throw new KafkaRegistrationException(
                    List.of("Generated companion " + companionFqn + " field '" + METAS_FIELD
                            + "' has unexpected type "
                            + (value == null ? "null" : value.getClass().getName())
                            + "; expected List<KafkaBindingMeta>"));
        }

        // Validate each element type
        for (Object element : list) {
            if (!(element instanceof KafkaBindingMeta)) {
                throw new KafkaRegistrationException(
                        List.of("Generated companion " + companionFqn + " field '" + METAS_FIELD
                                + "' contains an element of unexpected type "
                                + (element == null ? "null" : element.getClass().getName())
                                + "; expected KafkaBindingMeta"));
            }
        }

        @SuppressWarnings("unchecked")
        List<KafkaBindingMeta> metas = (List<KafkaBindingMeta>) list;
        return metas;
    }

    // --- Conversion: Model 1 (Source Binding) ---

    /**
     * Converts a {@link KafkaBindingMeta} of kind {@link KafkaBindingMeta.Kind#SOURCE} representing
     * a Model-1 {@code @KafkaSource} into a {@link ConsumerEntry}, mirroring the resolution logic
     * of {@link KafkaConsumerScanner#scanKafkaSources}.
     *
     * <p>The binding name is derived by the same rules as the scanner: an explicit
     * {@link KafkaBindingMeta#name()} wins; otherwise the name is
     * {@code "{type}-{serviceName}-{operationId}"} when a type segment is present, or
     * {@code "{serviceName}-{operationId}"} otherwise.
     *
     * <p>Target resolution uses {@link ServiceTargetResolver#resolve(Class, String)} when the
     * operation has a stable target id (indicated by a non-null {@link ServiceMethodMeta#stableTargetId()});
     * otherwise the address falls back to {@link ServiceMethodMeta#address()}.
     *
     * @param meta the precomputed binding metadata
     * @param contractEntry the service contract entry for the origin service
     * @param resolver the service target resolver for address and stable-id lookup
     * @param serdeRegistry the serde registry used to resolve format and build the deserializer
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry /
     *     connection property bags)
     * @param consumerIndex the immutable {@code name -> KafkaConsumerConfig} index for per-consumer
     *     config lookup by the derived binding name
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     * @return the built entry, or {@code null} if any per-entry violation was recorded
     */
    @Nullable
    static ConsumerEntry toSourceEntry(
            KafkaBindingMeta meta,
            ContractEntry<?> contractEntry,
            ServiceTargetResolver resolver,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig,
            Map<String, KafkaConsumerConfig> consumerIndex,
            Set<String> usedNames,
            List<String> violations) {

        // Find the ServiceMethodMeta by the target operation id
        String targetOp = meta.targetOperation();
        ServiceMethodMeta methodMeta = contractEntry.operations().get(targetOp);
        if (methodMeta == null) {
            // Try matching by method name (operation may use a @ServiceOperation alias)
            for (ServiceMethodMeta m : contractEntry.operations().values()) {
                if (m.method().name().equals(targetOp)) {
                    methodMeta = m;
                    break;
                }
            }
        }
        if (methodMeta == null) {
            violations.add("Generated binding meta references operation '" + targetOp + "' not found on "
                    + contractEntry.contract().getSimpleName());
            return null;
        }

        // Derive binding name: explicit > type-qualified > plain
        String bindingName = KafkaConsumerValidation.deriveBindingName(
                meta.name(), contractEntry.namespace(), contractEntry.name(), methodMeta.operation());

        // Resolve address and stable target id via ServiceTargetResolver when eligible
        KafkaConsumerValidation.ResolvedTarget resolved =
                KafkaConsumerValidation.resolveTarget(methodMeta, contractEntry, resolver);
        String targetAddress = resolved.address();
        String stableTargetId = resolved.stableTargetId();

        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                bindingName,
                meta.topic(),
                meta.groupId(),
                true,
                meta.commitStrategy(),
                meta.errorStrategy(),
                meta.deadLetterTopic(),
                30_000L,
                // SOURCE metas always carry null here (@KafkaSource has no jsonProfile),
                // matching the reflective scanKafkaSources path; config still wins if present.
                meta.jsonProfile(),
                kafkaConfig,
                consumerIndex.get(bindingName));

        return KafkaConsumerValidation.validateAndBuild(
                bindingName,
                config,
                ConsumerEntry.Kind.BINDING,
                methodMeta.payloadType(),
                targetAddress,
                stableTargetId,
                methodMeta.oneWay(),
                List.of(),
                null,
                serdeRegistry,
                null,
                null,
                usedNames,
                violations);
    }

    // --- Conversion: Model 3 (Router) ---

    /**
     * Converts a {@link KafkaBindingMeta} of kind {@link KafkaBindingMeta.Kind#ROUTER} into a
     * router {@link ConsumerEntry}, mirroring the resolution logic of
     * {@code KafkaConsumerScanner.processRouterClass}.
     *
     * <p>For each {@link KafkaBindingMeta.RouteMeta}, if a {@code targetService} is specified,
     * address resolution uses {@link ServiceTargetResolver#resolve(Class, String)} first. If the
     * resolver throws (operation registered without {@link dev.vertique.services.ServiceOperation}),
     * resolution falls back to a direct registry lookup by operation name for backward compatibility.
     *
     * <p>Match-rule validation (mutual exclusivity of {@code matchHeader}, {@code matchProperty},
     * and {@code defaultHandler}) is performed at compile time by the codegen processor and is NOT
     * repeated here.
     *
     * @param meta the precomputed router metadata
     * @param registry the service contract registry for dispatch target validation
     * @param resolver the service target resolver for address and stable-id lookup
     * @param serdeRegistry the serde registry (passed through to validation; router format is JSON
     *     until slice 4 generalizes it)
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry /
     *     connection property bags)
     * @param consumerIndex the immutable {@code name -> KafkaConsumerConfig} index for per-consumer
     *     config lookup by router name
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     * @return the built entry, or {@code null} if any per-entry violation was recorded
     */
    @Nullable
    static ConsumerEntry toRouterEntry(
            KafkaBindingMeta meta,
            ServiceContractRegistry registry,
            ServiceTargetResolver resolver,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig,
            Map<String, KafkaConsumerConfig> consumerIndex,
            Set<String> usedNames,
            List<String> violations) {

        String name = meta.name();

        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                name,
                meta.topic(),
                meta.groupId(),
                true,
                meta.commitStrategy(),
                meta.errorStrategy(),
                meta.deadLetterTopic(),
                30_000L,
                // @JsonProfile default for this router; config still wins if present
                meta.jsonProfile(),
                kafkaConfig,
                consumerIndex.get(name));

        List<ConsumerEntry.RouteEntry> routes = new ArrayList<>();

        for (KafkaBindingMeta.RouteMeta routeMeta : meta.routes()) {
            String targetAddress = null;
            String routeStableTargetId = null;
            boolean targetOneWay = false;

            if (routeMeta.targetService() != null) {
                // Validate that the service is registered
                ContractEntry<?> targetEntry;
                try {
                    targetEntry = registry.resolve(routeMeta.targetService());
                } catch (IllegalArgumentException e) {
                    violations.add("[" + name + "] Route target service "
                            + routeMeta.targetService().getSimpleName() + " is not registered");
                    continue;
                }

                // Resolve address via ServiceTargetResolver; fall back to registry lookup
                try {
                    ResolvedServiceTarget resolved =
                            resolver.resolve(routeMeta.targetService(), routeMeta.targetOperation());
                    targetAddress = resolved.address();
                    routeStableTargetId = resolved.targetId();
                    targetOneWay = resolved.meta().oneWay();
                } catch (IllegalArgumentException resolverEx) {
                    // Fall back: operation registered without @ServiceOperation
                    ServiceMethodMeta opMeta = targetEntry.operations().get(routeMeta.targetOperation());
                    if (opMeta == null) {
                        violations.add("[" + name + "] Route @DispatchTo operation '"
                                + routeMeta.targetOperation() + "' not found on "
                                + routeMeta.targetService().getSimpleName());
                        continue;
                    }
                    targetAddress = opMeta.address();
                    routeStableTargetId = null;
                    targetOneWay = opMeta.oneWay();
                }
            }

            routes.add(new ConsumerEntry.RouteEntry(
                    routeMeta.matchHeader(),
                    routeMeta.matchProperty(),
                    routeMeta.matchValue(),
                    routeMeta.defaultHandler(),
                    routeMeta.valueType(),
                    targetAddress,
                    routeStableTargetId,
                    targetOneWay));
        }

        return KafkaConsumerValidation.validateAndBuild(
                name,
                config,
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                routes,
                null,
                serdeRegistry,
                null,
                null,
                usedNames,
                violations);
    }

    // --- Conversion: Model 4 (Handler) ---

    /**
     * Converts a {@link KafkaBindingMeta} of kind {@link KafkaBindingMeta.Kind#HANDLER} combined
     * with a live {@link KafkaRecordHandler} instance into a {@link ConsumerEntry}, mirroring the
     * resolution logic of {@code KafkaConsumerScanner.processHandlerInstance}.
     *
     * <p>Unlike the reflective path, the value type is read directly from
     * {@link KafkaBindingMeta#valueType()} (precomputed at compile time), skipping the
     * reflective {@code TypeResolver.resolveTypeArgument} call.
     *
     * @param <V> the handler value type
     * @param meta the precomputed handler metadata
     * @param handler the live handler instance contributed via Dagger
     * @param serdeRegistry the serde registry used to resolve format and build the deserializer
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format / schema-registry /
     *     connection property bags)
     * @param consumerIndex the immutable {@code name -> KafkaConsumerConfig} index for per-consumer
     *     config lookup by handler name
     * @param usedNames mutable set of already-claimed binding names (updated in place)
     * @param violations mutable list to collect violation messages
     * @return the built entry, or {@code null} if any per-entry violation was recorded
     */
    @Nullable
    static <V> ConsumerEntry toHandlerEntry(
            KafkaBindingMeta meta,
            KafkaRecordHandler<V> handler,
            KafkaSerdeRegistry serdeRegistry,
            KafkaConfig kafkaConfig,
            Map<String, KafkaConsumerConfig> consumerIndex,
            Set<String> usedNames,
            List<String> violations) {

        String name = meta.name();

        @SuppressWarnings("unchecked")
        Class<V> valueType = (Class<V>) meta.valueType();

        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                name,
                meta.topic(),
                meta.groupId(),
                true,
                meta.commitStrategy(),
                meta.errorStrategy(),
                meta.deadLetterTopic(),
                30_000L,
                // @JsonProfile default for this handler; config still wins if present
                meta.jsonProfile(),
                kafkaConfig,
                consumerIndex.get(name));

        return KafkaConsumerValidation.validateAndBuild(
                name,
                config,
                ConsumerEntry.Kind.HANDLER,
                valueType,
                null,
                null,
                false,
                List.of(),
                handler,
                serdeRegistry,
                null,
                null,
                usedNames,
                violations);
    }
}
