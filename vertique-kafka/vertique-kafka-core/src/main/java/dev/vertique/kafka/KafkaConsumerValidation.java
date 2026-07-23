// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared validation, name-derivation, target-resolution, and construction logic for all four
 * Kafka consumption models.
 *
 * <p>This class is package-private and holds static helper methods used by both
 * {@link KafkaConsumerScanner} (reflective path) and {@link GeneratedBindingMetaLoader} (codegen
 * path) so that the logic is defined exactly once.
 *
 * <p>Cross-entry violations (duplicate names) and per-entry violations (blank topic, blank
 * group ID, strategy incompatibilities, and — for routers — duplicate route selectors) are
 * accumulated into the caller-supplied {@code violations} list so the caller can report all
 * violations together. The duplicate-route-selector check mirrors the codegen
 * {@code HandlerMatchValidator} so a reflectively-scanned and a generated router are rejected
 * identically.
 */
@Slf4j
final class KafkaConsumerValidation {

    private KafkaConsumerValidation() {}

    // --- Binding Name Derivation ---

    /**
     * Derives the consumer binding name from an explicit raw name, a type-qualified default, or a
     * plain default, applying the following priority rule in order:
     * <ol>
     *   <li>If {@code rawName} is non-null and non-blank, it is returned as-is.</li>
     *   <li>If {@code type} is non-null and non-blank, the name is
     *       {@code "{type}-{serviceName}-{operationId}"}.</li>
     *   <li>Otherwise the name is {@code "{serviceName}-{operationId}"}.</li>
     * </ol>
     *
     * @param rawName     the explicit name (from {@link KafkaSource#name()} or
     *                    {@link KafkaBindingMeta#name()}); {@code null} or blank means "derive"
     * @param type        the service type segment (from {@link ContractEntry#type()}); {@code null}
     *                    or blank means no type prefix
     * @param serviceName the service name segment (from {@link ContractEntry#name()})
     * @param operationId the durable operation id (from {@link ServiceMethodMeta#operation()})
     * @return the resolved binding name; never {@code null}
     */
    static String deriveBindingName(String rawName, String type, String serviceName, String operationId) {
        if (rawName != null && !rawName.isBlank()) {
            return rawName;
        }
        if (type != null && !type.isBlank()) {
            return type + "-" + serviceName + "-" + operationId;
        }
        return serviceName + "-" + operationId;
    }

    // --- Target Resolution ---

    /**
     * The resolved address and stable target id for a single service operation.
     *
     * @param address        the runtime event-bus dispatch address
     * @param stableTargetId the durable stable target id, or {@code null} when the operation was
     *                       not registered with {@link dev.vertique.services.ServiceOperation}
     */
    record ResolvedTarget(String address, String stableTargetId) {}

    /**
     * Resolves the event-bus address and stable target id for a service operation.
     *
     * <p>When the operation carries a stable target id (i.e. {@link ServiceMethodMeta#stableTargetId()}
     * is non-null), {@link ServiceTargetResolver#resolve(Class, String)} is called and its result
     * provides both {@code address} and {@code stableTargetId}. Otherwise, the address falls back
     * to {@link ServiceMethodMeta#address()} and the stable target id is {@code null}.
     *
     * @param methodMeta    the operation metadata
     * @param contractEntry the service contract entry that owns the operation
     * @param resolver      the service target resolver
     * @return a {@link ResolvedTarget} containing the address and stable target id
     */
    static ResolvedTarget resolveTarget(
            ServiceMethodMeta methodMeta, ContractEntry<?> contractEntry, ServiceTargetResolver resolver) {
        if (methodMeta.stableTargetId() != null) {
            ResolvedServiceTarget resolved = resolver.resolve(contractEntry.contract(), methodMeta.operation());
            return new ResolvedTarget(resolved.address(), resolved.targetId());
        }
        return new ResolvedTarget(methodMeta.address(), null);
    }

    // --- Validation and Construction ---

    /**
     * Validates the resolved config and name uniqueness, selects the effective format and
     * deserializer via the {@link KafkaSerdeRegistry}, enforces event-loop safety threading,
     * and builds a {@link ConsumerEntry} if no violations are found for this entry.
     *
     * <p>Cross-entry violations such as duplicate names are recorded immediately. Per-entry
     * violations (blank topic, blank groupId, RETRY without MANUAL, DEAD_LETTER without AUTO
     * commit, incompatible format+custom-deserializer combinations, and
     * {@code worker=false} with a blocking deserializer) are also recorded.
     *
     * <p>Format/deserializer selection rules (applied after structural validation):
     * <ul>
     *   <li>{@link ConsumerEntry.Kind#ROUTER} — resolves a single value format across all non-Void
     *       routes ({@code resolveRouterFormat}); no per-entry deserializer is built (the verticle
     *       builds per-route and routing deserializers).</li>
     *   <li>{@link ConsumerEntry.Kind#BINDING} or {@link ConsumerEntry.Kind#HANDLER} with a
     *       non-null {@code customDeserializer} — the custom deserializer wins; an
     *       endpoint-level {@code format} alongside it is rejected as ambiguous; a global
     *       {@code kafka.format} is silently ignored for it.</li>
     *   <li>Otherwise (auto / explicit format via registry) — {@link KafkaSerdeRegistry#resolveFormat}
     *       resolves the format, then {@link KafkaSerdeRegistry#deserializer} builds the serde.</li>
     * </ul>
     *
     * <p>Event-loop safety (NFR-AVRO-005): when the effective deserializer reports
     * {@link KafkaDeserializer#mayBlock()}, the consumer is forced onto the worker threading model
     * unless {@code worker=false} was set explicitly (which is a violation).
     *
     * @param name the binding name
     * @param config the resolved consumer config
     * @param kind the consumption kind
     * @param valueType the deserialization target type; {@code null} for
     *     {@link ConsumerEntry.Kind#ROUTER}
     * @param targetAddress the event bus dispatch address; {@code null} for
     *     {@link ConsumerEntry.Kind#ROUTER} and {@link ConsumerEntry.Kind#HANDLER}
     * @param stableTargetId the durable stable target id; {@code null} if not available
     * @param targetOneWay whether the dispatch is fire-and-forget
     * @param routes route entries for {@link ConsumerEntry.Kind#ROUTER} kind; empty for other kinds
     * @param handler the handler instance for {@link ConsumerEntry.Kind#HANDLER} kind;
     *     {@code null} for other kinds
     * @param registry the serde registry used to resolve format and build deserializers
     * @param customDeserializer caller-supplied deserializer (Model-2 only); {@code null} for
     *     all other models
     * @param filter the pre-deserialization filter, or {@code null} for accept-all
     * @param usedNames mutable set of claimed names (updated if name is new)
     * @param violations mutable list to collect violation messages
     * @return the built entry, or {@code null} if any per-entry violation was recorded
     */
    static ConsumerEntry validateAndBuild(
            String name,
            ResolvedKafkaConsumerConfig config,
            ConsumerEntry.Kind kind,
            Class<?> valueType,
            String targetAddress,
            String stableTargetId,
            boolean targetOneWay,
            List<ConsumerEntry.RouteEntry> routes,
            KafkaRecordHandler<?> handler,
            KafkaSerdeRegistry registry,
            KafkaDeserializer<?> customDeserializer,
            KafkaRecordFilter filter,
            Set<String> usedNames,
            List<String> violations) {

        boolean valid = true;

        // Duplicate name check
        if (!usedNames.add(name)) {
            violations.add("Duplicate Kafka consumer binding name: '" + name + "'");
            valid = false;
        }

        // Topic must not be blank
        if (config.topic() == null || config.topic().isBlank()) {
            violations.add("[" + name + "] Topic must not be blank");
            valid = false;
        }

        // Group ID must not be blank
        if (config.groupId() == null || config.groupId().isBlank()) {
            violations.add("[" + name + "] Group ID must not be blank");
            valid = false;
        }

        // RETRY strategy requires MANUAL commit
        if (config.errorStrategy() == ErrorStrategy.RETRY && config.commitStrategy() != CommitStrategy.MANUAL) {
            violations.add("[" + name + "] ErrorStrategy.RETRY requires CommitStrategy.MANUAL");
            valid = false;
        }

        // DEAD_LETTER strategy requires MANUAL commit to guarantee DLQ publish before offset advance
        if (config.errorStrategy() == ErrorStrategy.DEAD_LETTER && config.commitStrategy() == CommitStrategy.AUTO) {
            violations.add("Consumer '" + name + "': DEAD_LETTER error strategy requires MANUAL commit strategy"
                    + " (AUTO commit may skip DLQ publishing on dispatch failures)");
            valid = false;
        }

        // Router routes must have unique selectors. resolveRoute matches header routes before property
        // routes (each pass in declaration order), so a second route with the same matchHeader+matchValue
        // (or matchProperty+matchValue) is unreachable within its pass. Mirror the codegen HandlerMatchValidator
        // (same effective wording, same structured key) so a reflectively-scanned and a generated router are
        // rejected identically. Selectors are namespaced by kind so a header and a property sharing a
        // name/value are not duplicates. This is selector uniqueness, not payload-type uniqueness.
        if (kind == ConsumerEntry.Kind.ROUTER) {
            Set<SelectorKey> selectorsSeen = new HashSet<>();
            for (ConsumerEntry.RouteEntry route : routes) {
                if (route.defaultHandler()) {
                    continue;
                }
                boolean hasMatchHeader =
                        route.matchHeader() != null && !route.matchHeader().isBlank();
                String selectorKind = hasMatchHeader ? "header" : "property";
                String selectorName = hasMatchHeader ? route.matchHeader() : route.matchProperty();
                // Structured key (field-wise equality), not a delimiter-joined string, so a name or value
                // containing the delimiter characters cannot forge a false collision.
                if (!selectorsSeen.add(new SelectorKey(selectorKind, selectorName, route.matchValue()))) {
                    violations.add("@KafkaListener " + name + " has multiple @KafkaHandler methods with the same "
                            + selectorKind + " selector '" + selectorName + "'='" + route.matchValue()
                            + "'; route selectors must be unique");
                    valid = false;
                }
            }
        }

        // --- Format, deserializer, and threading resolution ---
        // Skipped entirely when the entry is already invalid, so a doomed entry never allocates a
        // registry-backed serde (which would leak — the method returns null below).

        String valueFormat = KafkaSerdeRegistry.DEFAULT_FORMAT;
        KafkaDeserializer<?> effectiveDeserializer = null;
        // True only when the registry built the deserializer (framework-owned). A Model-2 custom
        // deserializer is user-owned and must never be closed by validation.
        boolean frameworkOwnedDeserializer = false;

        if (valid) {
            if (kind == ConsumerEntry.Kind.ROUTER) {
                // A router has multiple route value types but one wire format per topic: resolve a single
                // format across all non-Void routes, failing fast on a mix or a missing provider
                // (FR-AVRO-005/010). Returns null (and records a violation) on conflict.
                String routerFormat = resolveRouterFormat(name, config, routes, registry, violations);
                if (routerFormat == null) {
                    valid = false;
                    routerFormat = KafkaSerdeRegistry.DEFAULT_FORMAT;
                }
                valueFormat = routerFormat;
                effectiveDeserializer = null;
            } else if (customDeserializer != null) {
                // Model-2 custom deserializer wins; format auto-detect does NOT apply to it.
                // Reject an ENDPOINT-LEVEL format alongside a custom deserializer (ambiguous);
                // a GLOBAL kafka.format is ignored for it (no rejection).
                if (config.endpointFormat() != null && !config.endpointFormat().isBlank()) {
                    violations.add("[" + name + "] a custom deserializer cannot be combined with an endpoint-level"
                            + " 'format' (kafka.consumers." + name + ".format='" + config.endpointFormat()
                            + "'); remove one");
                    valid = false;
                }
                valueFormat = KafkaSerdeRegistry.DEFAULT_FORMAT; // not consulted for non-router dispatch
                effectiveDeserializer = customDeserializer;
            } else if (valueType != null) {
                // resolveFormat can throw on an ambiguous auto-detect (two providers claim the type);
                // convert that to a recorded violation so registration fails cleanly with all other
                // violations rather than escaping this collect-and-return-null path.
                try {
                    valueFormat = registry.resolveFormat(
                            valueType, formatLookup(config.endpointFormat()), config.globalFormat());
                } catch (IllegalArgumentException e) {
                    violations.add("[" + name + "] cannot resolve value format for " + valueType.getName() + ": "
                            + e.getMessage());
                    valid = false;
                    valueFormat = KafkaSerdeRegistry.DEFAULT_FORMAT;
                }
                if (valid && config.enabled()) {
                    KafkaDeserializer<?> built = null;
                    try {
                        built = registry.deserializer(valueFormat, valueType, config.serdeConfig());
                    } catch (IllegalArgumentException | JsonProfileConfigurationException e) {
                        // IllegalArgumentException: no provider / unsupported type.
                        // JsonProfileConfigurationException: an unknown jsonProfile id, raised by the
                        // JSON provider when it resolves the profile mapper at deserializer-build time —
                        // it is NOT an IllegalArgumentException, so it must be caught explicitly here or it
                        // would escape this collect-and-return-null path on enabled consumers.
                        violations.add("[" + name + "] requests value format '" + valueFormat + "': " + e.getMessage());
                        valid = false;
                    }
                    effectiveDeserializer = built;
                    frameworkOwnedDeserializer = built != null;
                } else if (valid) {
                    // A disabled consumer is never deployed — validate the config the SAME way the enabled
                    // path does (fail-fast parity): build the registry-backed deserializer and close it
                    // immediately so nothing leaks. Building it subsumes the provider-existence and
                    // type-support checks (provider.deserializer() rejects an unregistered format and an
                    // unsupported type) AND validates the jsonProfile (the JSON provider resolves the
                    // profile mapper at build time, throwing JsonProfileConfigurationException for an
                    // unknown id). The probe deserializer is closed and discarded — it is never assigned to
                    // effectiveDeserializer because a disabled consumer is never deployed. provider.mayBlock()
                    // (via the provider-level flag) still drives the worker check below.
                    KafkaDeserializer<?> probe = null;
                    try {
                        probe = registry.deserializer(valueFormat, valueType, config.serdeConfig());
                    } catch (IllegalArgumentException | JsonProfileConfigurationException e) {
                        violations.add("[" + name + "] requests value format '" + valueFormat + "': " + e.getMessage());
                        valid = false;
                    } finally {
                        if (probe != null) {
                            try {
                                probe.close();
                            } catch (RuntimeException closeFailure) {
                                log.warn(
                                        "[{}] failed to close the {} validation probe deserializer;"
                                                + " a serde resource may have leaked",
                                        name,
                                        valueFormat,
                                        closeFailure);
                            }
                        }
                    }
                }
            } else {
                valueFormat = KafkaSerdeRegistry.DEFAULT_FORMAT;
                effectiveDeserializer = null; // no value type (e.g. Void payload)
            }

            // Event-loop safety (NFR-AVRO-005): force worker threading when the effective serde may block.
            // For routers there is no per-entry deserializer, so the provider-level flag for the router's
            // resolved format is used; for other kinds the effective deserializer's flag is used. An
            // explicit worker=false paired with a blocking serde is a violation.
            //
            // Guarded by `valid`: a format-resolution / deserializer-build / provider-support failure
            // above already flipped `valid` to false and the entry will return null below, so this
            // block must not run on a rejected entry — otherwise it could add a spurious second
            // violation or mutate the threading model on an entry that is discarded anyway ("invalid
            // entries have no side effects").
            if (valid) {
                boolean mayBlock;
                if (kind == ConsumerEntry.Kind.ROUTER) {
                    mayBlock = providerMayBlock(valueFormat, registry);
                } else if (effectiveDeserializer != null) {
                    mayBlock = effectiveDeserializer.mayBlock();
                } else {
                    // No serde was built (disabled consumer, or no value type) — fall back to the
                    // provider-level flag so a disabled mayBlock consumer is validated against
                    // worker=false consistently with the enabled path.
                    mayBlock = providerMayBlock(valueFormat, registry);
                }
                if (mayBlock) {
                    if (Boolean.FALSE.equals(config.workerConfigured())) {
                        violations.add("[" + name + "] worker=false is incompatible with a blocking value format ('"
                                + valueFormat + "'); a mayBlock deserializer must run on a worker thread"
                                + " (remove worker=false)");
                        valid = false;
                    } else if (config.deploymentOptions().getThreadingModel() != ThreadingModel.WORKER) {
                        config.deploymentOptions().setThreadingModel(ThreadingModel.WORKER);
                    }
                }
            }
        }

        if (!valid) {
            // A later validation step (e.g. worker=false vs a mayBlock format) rejected the entry after
            // the registry already built the deserializer — close it so its registry client does not
            // leak. User-provided custom deserializers are left untouched (user-owned).
            if (frameworkOwnedDeserializer && effectiveDeserializer != null) {
                try {
                    effectiveDeserializer.close();
                } catch (RuntimeException closeFailure) {
                    log.warn(
                            "[{}] failed to close the rejected {} consumer's deserializer;"
                                    + " a serde resource may have leaked",
                            name,
                            valueFormat,
                            closeFailure);
                }
            }
            return null;
        }

        return new ConsumerEntry(
                name,
                config,
                kind,
                valueType,
                targetAddress,
                stableTargetId,
                targetOneWay,
                routes,
                handler,
                effectiveDeserializer,
                frameworkOwnedDeserializer,
                filter,
                valueFormat);
    }

    /**
     * Resolves the single value format shared by all non-{@code Void} routes of a router (one topic =
     * one wire format). Records a violation and returns {@code null} if routes resolve to different
     * formats, or if the resolved format names a provider that is not registered (fail-fast). Returns
     * {@link KafkaSerdeRegistry#DEFAULT_FORMAT} when the router has no non-{@code Void} routes.
     *
     * <p>Provider existence and per-route type support are required whenever the router has at least
     * one property route or at least one non-Void payload route. A header/default-only all-Void
     * router with no property route requires no provider (there is nothing to deserialize).
     *
     * @param name the router binding name (for violation messages)
     * @param config the resolved config (for endpoint/global format provenance)
     * @param routes the router's routes
     * @param registry the serde registry
     * @param violations mutable list to collect violation messages
     * @return the single resolved format, or {@code null} on a mixed-format / missing-provider violation
     */
    private static String resolveRouterFormat(
            String name,
            ResolvedKafkaConsumerConfig config,
            List<ConsumerEntry.RouteEntry> routes,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        JsonObject fmtLookup = formatLookup(config.endpointFormat());
        String routerFormat = null;
        for (ConsumerEntry.RouteEntry route : routes) {
            Class<?> routeType = route.valueType();
            if (routeType == null || routeType == Void.class) {
                continue;
            }
            String routeFormat;
            try {
                routeFormat = registry.resolveFormat(routeType, fmtLookup, config.globalFormat());
            } catch (IllegalArgumentException e) {
                violations.add("[" + name + "] router cannot resolve value format for route type " + routeType.getName()
                        + ": " + e.getMessage());
                return null;
            }
            if (routerFormat == null) {
                routerFormat = routeFormat;
            } else if (!routerFormat.equals(routeFormat)) {
                violations.add("@KafkaListener " + name + " has mixed value formats across routes ('" + routerFormat
                        + "' and '" + routeFormat + "'); one topic must use a single wire format");
                return null;
            }
        }
        if (routerFormat == null) {
            // No payload-bearing route to auto-detect from (e.g. all routes are no-arg property
            // handlers). Honor an explicit endpoint/global format so an avro router still routes (and
            // forces worker threading) instead of silently falling back to the default format.
            // resolveFormat with Void resolves explicit > global, falling through to DEFAULT_FORMAT
            // when neither is set (Void is never auto-detected, so this call cannot hit the ambiguous
            // branch today — wrapped defensively for parity should that ever change).
            try {
                routerFormat = registry.resolveFormat(Void.class, fmtLookup, config.globalFormat());
            } catch (IllegalArgumentException e) {
                violations.add("[" + name + "] router cannot resolve value format: " + e.getMessage());
                return null;
            }
        }

        // Require a registered provider when the router has at least one property route or at least
        // one non-Void payload route. An all-Void header/default-only router needs no provider.
        boolean hasPropertyRoute = routes.stream()
                .anyMatch(r -> r.matchProperty() != null && !r.matchProperty().isBlank());
        boolean hasPayloadRoute = routes.stream().anyMatch(r -> r.valueType() != null && r.valueType() != Void.class);

        if (hasPropertyRoute || hasPayloadRoute) {
            KafkaSerdeProvider provider;
            try {
                provider = registry.provider(routerFormat); // throws IllegalArgumentException if no provider
            } catch (IllegalArgumentException e) {
                violations.add("[" + name + "] router requests value format '" + routerFormat + "': " + e.getMessage());
                return null;
            }
            // Fail fast at registration if a non-Void route type is not valid for the resolved format
            // (e.g. an explicit avro router with a plain-POJO route), instead of failing later at deploy.
            for (ConsumerEntry.RouteEntry route : routes) {
                Class<?> routeType = route.valueType();
                if (routeType != null && routeType != Void.class && !provider.supports(routeType)) {
                    violations.add("[" + name + "] route value type " + routeType.getName()
                            + " is not valid for format '" + routerFormat + "'");
                    return null;
                }
            }
            // Profile probe: build the SAME serdes the verticle will build at runtime — a per-route
            // deserializer for each non-Void payload route (KafkaConsumerVerticle.buildRouteDeserializers)
            // and the type-agnostic routing deserializer when there is a property route
            // (KafkaRecordDispatcher) — using the router's runtime serde-config bag
            // (config.serdeConfig(), which carries the resolved jsonProfile id). For the JSON format this
            // resolves the profile mapper and throws JsonProfileConfigurationException for an unknown id,
            // so an unknown @JsonProfile id fails fast at the VALIDATE phase, mirroring the non-router lane
            // (validateAndBuild ~:273-285) instead of deferring to deserializer construction at deploy.
            // The probe serdes are closed and discarded so registry-backed clients do not leak.
            if (!probeRouterProfile(name, config, routes, routerFormat, hasPropertyRoute, registry, violations)) {
                return null;
            }
        }

        return routerFormat;
    }

    /**
     * Builds and immediately closes the router's runtime serdes as a validation probe so a
     * deserializer-build failure — notably an unknown {@code @JsonProfile} id surfaced as a
     * {@link JsonProfileConfigurationException} — is caught at the VALIDATE phase rather than deferred
     * to deployment/dispatch. Mirrors the per-route deserializers built by
     * {@code KafkaConsumerVerticle.buildRouteDeserializers} (one per non-{@code Void} payload route)
     * and the routing deserializer built by {@code KafkaRecordDispatcher} (only when the router has a
     * property route), using the same {@link ResolvedKafkaConsumerConfig#serdeConfig()} bag — which
     * carries the resolved {@code jsonProfile} id — so the JSON provider resolves the profile mapper
     * exactly as it will at runtime.
     *
     * @param name the router binding name (for violation messages)
     * @param config the resolved config (provides the runtime serde-config bag)
     * @param routes the router's routes
     * @param routerFormat the single resolved value format for the router
     * @param hasPropertyRoute whether the router has at least one {@code matchProperty} route
     * @param registry the serde registry
     * @param violations mutable list to collect violation messages
     * @return {@code true} if every probe serde built cleanly; {@code false} (with a recorded violation)
     *     if any probe build failed
     */
    private static boolean probeRouterProfile(
            String name,
            ResolvedKafkaConsumerConfig config,
            List<ConsumerEntry.RouteEntry> routes,
            String routerFormat,
            boolean hasPropertyRoute,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        List<KafkaDeserializer<?>> probes = new ArrayList<>();
        try {
            Set<Class<?>> probedTypes = new HashSet<>();
            for (ConsumerEntry.RouteEntry route : routes) {
                Class<?> routeType = route.valueType();
                if (routeType != null && routeType != Void.class && probedTypes.add(routeType)) {
                    probes.add(registry.deserializer(routerFormat, routeType, config.serdeConfig()));
                }
            }
            if (hasPropertyRoute) {
                // A format whose provider does not support property routing throws
                // UnsupportedOperationException here. That is a pre-existing concern surfaced by the
                // runtime dispatcher (KafkaRecordDispatcher), NOT part of the profile fail-fast gap this
                // probe closes — tolerate it so the probe does not newly reject such routers. The probe's
                // purpose is to resolve the jsonProfile (and provider/type support); a provider that
                // overrides routingDeserializer (e.g. the real JSON provider) still resolves the profile
                // inside it and fails fast on an unknown id below.
                try {
                    probes.add(registry.routingDeserializer(routerFormat, config.serdeConfig()));
                } catch (UnsupportedOperationException unsupportedRouting) {
                    // Property routing unsupported for this format — left to the runtime, unchanged.
                }
            }
            return true;
        } catch (IllegalArgumentException | JsonProfileConfigurationException e) {
            // IllegalArgumentException: no provider / unsupported type (already largely covered above,
            // caught defensively for parity with the non-router lane).
            // JsonProfileConfigurationException: an unknown jsonProfile id, raised by the JSON provider
            // when it resolves the profile mapper at deserializer-build time — it is NOT an
            // IllegalArgumentException, so it must be caught explicitly here or it would escape the
            // collect-violations-and-return-null path on routers (the non-router fail-fast gap).
            violations.add("[" + name + "] router requests value format '" + routerFormat + "': " + e.getMessage());
            return false;
        } finally {
            for (KafkaDeserializer<?> probe : probes) {
                try {
                    probe.close();
                } catch (RuntimeException closeFailure) {
                    log.warn(
                            "[{}] failed to close the {} router validation probe deserializer;"
                                    + " a serde resource may have leaked",
                            name,
                            routerFormat,
                            closeFailure);
                }
            }
        }
    }

    /**
     * Builds the endpoint-config view {@link KafkaSerdeRegistry#resolveFormat} reads the explicit
     * {@code "format"} from — carrying the endpoint-level format when set, else empty.
     *
     * @param endpointFormat the endpoint-level format, or {@code null}
     * @return a lookup {@code JsonObject}
     */
    private static JsonObject formatLookup(String endpointFormat) {
        JsonObject lookup = new JsonObject();
        if (endpointFormat != null) {
            lookup.put("format", endpointFormat);
        }
        return lookup;
    }

    /**
     * Whether a resolved format may block (provider-level flag). Attempts to look up the provider;
     * returns {@code false} when the provider is not registered (an unregistered format is already
     * reported as a violation on the path that resolved it). Used for routers (no per-route serde is
     * built before selection) and for non-router consumers with no built serde (a disabled consumer,
     * or a {@code Void} payload).
     *
     * @param valueFormat the resolved format
     * @param registry the serde registry
     * @return {@code true} if the format's provider may block
     */
    private static boolean providerMayBlock(String valueFormat, KafkaSerdeRegistry registry) {
        try {
            return registry.mayBlock(valueFormat);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Structured route-selector identity used for duplicate detection: the selector kind
     * ({@code "header"} / {@code "property"}), the header/property name, and the match value. Mirrors the
     * codegen-side {@code HandlerMatchValidator.SelectorKey}; using a record (field-wise equality) rather
     * than a delimiter-joined string avoids a false collision when a name or value contains the delimiter
     * characters.
     *
     * @param kind  the selector kind, {@code "header"} or {@code "property"}
     * @param name  the header or property name
     * @param value the match value
     */
    private record SelectorKey(String kind, String name, String value) {}
}
