// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.resilience.annotation.CircuitBreakerDeclaration;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.RetryDeclaration;
import dev.vertique.resilience.annotation.TimeoutDeclaration;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig.ServiceKey;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Central registry of service contracts, built once at startup from a set of service implementations.
 *
 * <p>Each entry maps a contract interface to its implementation instance, base address,
 * operation metadata, and deployment options. The registry is built by {@link ServiceRegistrar}
 * and used by the deployment manager and client factory.
 *
 * <p>After building all entries the registry also maintains a {@link #targetIndex()} —
 * a map from stable target id to {@link ServiceMethodMeta} — covering only operations where
 * {@code ServiceMethodMeta.stableTargetId()} is non-null (i.e., genuine service contracts).
 * Non-service entries such as delayed-job contributors that set an explicit address bypass
 * target id assignment and are therefore absent from the index.
 *
 * <p>Create instances via the static {@link #build(Set)}, {@link #build(Set, JsonObject)},
 * {@link #build(Set, Set, JsonObject)}, or
 * {@link #build(Set, Set, JsonObject, Map)} factory methods. The constructor is private.
 *
 * <p>Deployment options ({@code instances}/{@code worker}) are read from the typed
 * {@code (namespace, name) -> }{@link ServiceConfig} index, not the raw config; the raw
 * {@link JsonObject} is only forwarded to {@link ServiceContractContributor#contribute(JsonObject)}
 * (a cross-module SPI that resolves its own deployment options from config).
 */
@Slf4j
public class ServiceContractRegistry {

    private final Map<Class<?>, ContractEntry<?>> entries;
    private final Map<String, ServiceMethodMeta> targetIndex;

    private ServiceContractRegistry(
            Map<Class<?>, ContractEntry<?>> entries, Map<String, ServiceMethodMeta> targetIndex) {
        this.entries = Map.copyOf(entries);
        this.targetIndex = Map.copyOf(targetIndex);
    }

    // --- Nested Types ---

    /**
     * A single entry in the service contract registry.
     *
     * @param <T> the contract interface type
     * @param contract the contract interface type token
     * @param serviceInstance the service implementation instance (may be a direct impl or a
     *     {@code ServiceHandler} — not necessarily assignable to T)
     * @param namespace the service namespace segment from {@link ServiceContract#namespace()}
     * @param name the service name segment from {@link ServiceContract#value()}
     * @param baseAddress base event bus address in the form {@code "services/{namespace}/{name}"} or
     *     {@code "services/{name}"}
     * @param stableContractId durable dot-delimited contract identity (e.g.
     *     {@code "{namespace}.{name}"} or {@code "{name}"}); {@code null} for non-service entries
     *     with an explicit address
     * @param operations operation metadata keyed by operation id
     * @param deploymentOptions Vert.x deployment options derived from configuration
     */
    public record ContractEntry<T>(
            Class<T> contract,
            Object serviceInstance,
            String namespace,
            String name,
            String baseAddress,
            @Nullable String stableContractId,
            Map<String, ServiceMethodMeta> operations,
            DeploymentOptions deploymentOptions) {}

    // --- Factory Methods ---

    /**
     * Builds the registry from the given set of service implementations using default deployment options.
     *
     * @param services the set of service implementation instances to register
     * @param parser the config parser used to parse the (empty) deployment-option config
     * @return a fully built registry
     * @throws ServiceRegistrationException if validation fails during scanning
     */
    public static ServiceContractRegistry build(Set<Object> services, ConfigParser parser) {
        return build(services, new JsonObject(), parser);
    }

    /**
     * Builds the registry from the given set of service implementations with config-based deployment options.
     *
     * <p>Delegates to {@link #build(Set, Set, JsonObject, ConfigParser)} with an empty contributors set.
     *
     * @param services the set of service implementation instances to register
     * @param config application configuration for deployment option overrides
     * @param parser the config parser used to parse the deployment-option index
     * @return a fully built registry
     * @throws ServiceRegistrationException if validation fails during scanning
     */
    public static ServiceContractRegistry build(Set<Object> services, JsonObject config, ConfigParser parser) {
        return build(services, Set.of(), config, parser);
    }

    /**
     * Builds the registry from service implementations and external contributors, parsing the
     * deployment-option index from the raw config at the boundary.
     *
     * <p>The typed {@code (namespace, name) -> }{@link ServiceConfig} index is derived from
     * {@code config} via
     * {@link dev.vertique.services.config.ServicesConfig#fromConfig(JsonObject, ConfigParser)};
     * the raw config is still forwarded to contributors. Prefer
     * {@link #build(Set, Set, JsonObject, Map)} when the typed index is already available (the Dagger
     * boundary path).
     *
     * @param services the set of service implementation instances to register
     * @param contributors external contributors providing additional contract entries
     * @param config application configuration; the index is parsed from it and it is forwarded to
     *     contributors
     * @param parser the config parser used to parse the deployment-option index from {@code config}
     * @return a fully built registry
     * @throws ServiceRegistrationException if duplicate contracts, address collisions, or stable
     *     target id collisions are found
     */
    public static ServiceContractRegistry build(
            Set<Object> services,
            Set<ServiceContractContributor> contributors,
            JsonObject config,
            ConfigParser parser) {
        return build(
                services,
                contributors,
                config,
                dev.vertique.services.config.ServicesConfig.fromConfig(config, parser)
                        .index());
    }

    /**
     * Builds the registry from service implementations and external contributors using a pre-parsed
     * typed deployment-option index.
     *
     * <p>Phase 1 scans all {@link ServiceContract}-annotated implementations; phase 2 merges
     * entries from contributors; phase 3 validates that no two operations share an event bus
     * address across the combined set; phase 4 builds the stable target id index and validates
     * that no two operations share the same stable target id.
     *
     * <p>Deployment options are read from {@code serviceConfigIndex} per service (config path
     * {@code services.contracts.{namespace}.{name}}):
     * <ul>
     *   <li>{@link ServiceConfig#instances()} — number of verticle instances, default {@code 1}</li>
     *   <li>{@link ServiceConfig#worker()} — deploy as worker verticle, default {@code false}</li>
     * </ul>
     * A service absent from the index uses the defaults. The raw {@code config} is forwarded
     * unchanged to contributors.
     *
     * @param services the set of service implementation instances to register
     * @param contributors external contributors providing additional contract entries
     * @param config application configuration forwarded to contributors for their own deployment
     *     option resolution
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index supplying
     *     this registry's own deployment-option overrides
     * @return a fully built registry
     * @throws ServiceRegistrationException if duplicate contracts, address collisions, or stable
     *     target id collisions are found
     */
    public static ServiceContractRegistry build(
            Set<Object> services,
            Set<ServiceContractContributor> contributors,
            JsonObject config,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        ServiceRegistrar registrar = new ServiceRegistrar();
        Map<Class<?>, List<ServiceMethodMeta>> scanned = registrar.scan(services);

        Map<Class<?>, ContractEntry<?>> entries = new LinkedHashMap<>();

        // Phase 1: Process default @ServiceContract entries
        for (Map.Entry<Class<?>, List<ServiceMethodMeta>> entry : scanned.entrySet()) {
            Class<?> contract = entry.getKey();
            List<ServiceMethodMeta> metas = entry.getValue();

            if (metas.isEmpty()) {
                continue;
            }

            ServiceMethodMeta first = metas.get(0);
            String namespace = first.namespace();
            String name = first.name();
            String baseAddress = ServiceAddressing.buildBaseAddress(namespace, name);
            String stableContractId = ServiceAddressing.buildStableContractId(namespace, name);

            Map<String, ServiceMethodMeta> operations = new LinkedHashMap<>();
            for (ServiceMethodMeta meta : metas) {
                operations.put(meta.operation(), meta);
            }

            DeploymentOptions deploymentOptions = buildDeploymentOptions(namespace, name, serviceConfigIndex);
            ContractEntry<?> contractEntry = buildEntry(
                    contract,
                    first.serviceInstance(),
                    namespace,
                    name,
                    baseAddress,
                    stableContractId,
                    operations,
                    deploymentOptions);
            entries.put(contract, contractEntry);
        }

        // Phase 2: Process contributor entries — collect all duplicate-key violations before throwing
        List<ServiceRegistrationViolation> contributorViolations = new ArrayList<>();
        for (ServiceContractContributor contributor : contributors) {
            List<ContractEntry<?>> contributed = contributor.contribute(config);
            if (contributed == null) {
                continue;
            }
            for (ContractEntry<?> entry : contributed) {
                if (entries.containsKey(entry.contract())) {
                    contributorViolations.add(new ServiceRegistrationViolation(
                            entry.contract(),
                            null,
                            "Duplicate contract key from contributor: "
                                    + entry.contract().getName()
                                    + " (already registered by default scan or another contributor)"));
                } else {
                    entries.put(entry.contract(), entry);
                }
            }
        }
        if (!contributorViolations.isEmpty()) {
            throw new ServiceRegistrationException(contributorViolations);
        }

        // Phase 3: Global address collision validation across ALL entries
        validateNoAddressCollisions(entries);

        // Phase 4: Build stable target id index and validate uniqueness
        Map<String, ServiceMethodMeta> targetIndex = buildTargetIndex(entries);

        ServiceContractRegistry registry = new ServiceContractRegistry(entries, targetIndex);
        registry.logDiagnostics();
        return registry;
    }

    /**
     * Validates that no two operations across all entries share the same event bus address.
     *
     * @param entries the merged entries to validate
     * @throws ServiceRegistrationException if address collisions are found
     */
    private static void validateNoAddressCollisions(Map<Class<?>, ContractEntry<?>> entries) {
        Map<String, Class<?>> addressOwners = new LinkedHashMap<>();
        List<ServiceRegistrationViolation> violations = new ArrayList<>();

        for (ContractEntry<?> entry : entries.values()) {
            for (ServiceMethodMeta meta : entry.operations().values()) {
                Class<?> existing = addressOwners.put(meta.address(), entry.contract());
                if (existing != null) {
                    violations.add(new ServiceRegistrationViolation(
                            entry.contract(),
                            null,
                            "Duplicate event bus address '" + meta.address() + "' — already registered by "
                                    + existing.getName()));
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations);
        }
    }

    /**
     * Builds the stable target id index from all entries, including only operations where
     * {@code ServiceMethodMeta.stableTargetId()} is non-null.
     *
     * <p>Also validates that no two operations share the same stable target id; all violations
     * are collected and reported together.
     *
     * @param entries the merged entries to index
     * @return an unmodifiable map from stable target id to operation metadata
     * @throws ServiceRegistrationException if stable target id collisions are found
     */
    private static Map<String, ServiceMethodMeta> buildTargetIndex(Map<Class<?>, ContractEntry<?>> entries) {
        Map<String, ServiceMethodMeta> index = new LinkedHashMap<>();
        Map<String, Class<?>> targetOwners = new LinkedHashMap<>();
        List<ServiceRegistrationViolation> violations = new ArrayList<>();

        for (ContractEntry<?> entry : entries.values()) {
            for (ServiceMethodMeta meta : entry.operations().values()) {
                String targetId = meta.stableTargetId();
                if (targetId == null) {
                    continue;
                }
                Class<?> existing = targetOwners.put(targetId, entry.contract());
                if (existing != null) {
                    violations.add(new ServiceRegistrationViolation(
                            entry.contract(),
                            null,
                            "Duplicate stable target id '" + targetId + "' — already registered by "
                                    + existing.getName()));
                } else {
                    index.put(targetId, meta);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations);
        }

        return index;
    }

    /**
     * Creates a typed {@link ContractEntry} without unchecked cast warnings at the call site.
     *
     * @param <T> the contract type
     * @param contract the contract interface
     * @param impl the implementation instance
     * @param namespace the service namespace segment
     * @param name the service name segment
     * @param baseAddress the base event bus address
     * @param stableContractId the durable contract identity, or {@code null} for non-service entries
     * @param operations the operations map
     * @param deploymentOptions Vert.x deployment options
     * @return the typed contract entry
     */
    private static <T> ContractEntry<T> buildEntry(
            Class<T> contract,
            Object impl,
            String namespace,
            String name,
            String baseAddress,
            @Nullable String stableContractId,
            Map<String, ServiceMethodMeta> operations,
            DeploymentOptions deploymentOptions) {
        return new ContractEntry<>(
                contract,
                impl,
                namespace,
                name,
                baseAddress,
                stableContractId,
                Map.copyOf(operations),
                deploymentOptions);
    }

    /**
     * Builds {@link DeploymentOptions} from the typed services index for the given service type and name.
     *
     * <p>Reads {@link ServiceConfig#instances()} and {@link ServiceConfig#worker()} from the entry at
     * {@code services.contracts.{namespace}.{name}}. A service absent from the index uses the
     * defaults: {@code instances=1}, default threading model.
     *
     * @param namespace the service namespace segment
     * @param name the service name segment
     * @param serviceConfigIndex the typed {@code (namespace, name) -> ServiceConfig} index
     * @return the deployment options for this service
     */
    private static DeploymentOptions buildDeploymentOptions(
            String namespace, String name, Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        ServiceConfig serviceConfig = serviceConfigIndex.get(new ServiceKey(namespace, name));
        int instances = serviceConfig != null ? serviceConfig.instances() : 1;
        boolean worker = serviceConfig != null && serviceConfig.worker();
        DeploymentOptions opts = new DeploymentOptions().setInstances(instances);
        if (worker) {
            opts.setThreadingModel(ThreadingModel.WORKER);
        }
        return opts;
    }

    // --- Query Methods ---

    /**
     * Resolves a registry entry by contract interface.
     *
     * @param <T> the contract type
     * @param contract the contract interface class to look up
     * @return the contract entry for the given interface
     * @throws IllegalArgumentException if no entry is registered for the given contract
     */
    @SuppressWarnings("unchecked")
    public <T> ContractEntry<T> resolve(Class<T> contract) {
        ContractEntry<?> entry = entries.get(contract);
        if (entry == null) {
            throw new IllegalArgumentException("No service registered for contract: " + contract.getName());
        }
        return (ContractEntry<T>) entry;
    }

    /**
     * Returns all registered entries.
     *
     * @return an unmodifiable collection of all contract entries
     */
    public Collection<ContractEntry<?>> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /**
     * Returns the stable target id index built at startup.
     *
     * <p>Contains only operations where {@link ServiceMethodMeta#stableTargetId()} is non-null.
     * Non-service entries (e.g., delayed-job contributors) are absent.
     *
     * @return an unmodifiable map from stable target id to operation metadata
     */
    Map<String, ServiceMethodMeta> targetIndex() {
        return targetIndex;
    }

    // --- Diagnostics ---

    /**
     * Logs a structured summary of all registered contracts and their operations at INFO level.
     */
    private void logDiagnostics() {
        if (!log.isInfoEnabled()) {
            return;
        }

        log.info("Service registry built: {} contract(s)", entries.size());

        for (ContractEntry<?> entry : entries.values()) {
            DeploymentOptions opts = entry.deploymentOptions();
            String implName = entry.serviceInstance().getClass().getSimpleName();
            String deployDesc = buildDeployDesc(opts);
            log.info("  {} [{}]{}", entry.baseAddress(), implName, deployDesc);

            for (ServiceMethodMeta meta : entry.operations().values()) {
                String policyDesc = buildPolicyDesc(meta.resilienceAnnotations());
                String oneWayTag = meta.oneWay() ? " [one-way]" : "";
                log.info("    {}{} → {}", meta.operation(), oneWayTag, policyDesc);
            }
        }
    }

    /**
     * Builds a concise deployment descriptor string for logging.
     *
     * @param opts the deployment options
     * @return a string like {@code " instances=2 worker=true"} or {@code " instances=1"}
     */
    private String buildDeployDesc(DeploymentOptions opts) {
        StringBuilder sb = new StringBuilder();
        sb.append(" instances=").append(opts.getInstances());
        if (opts.getThreadingModel() == ThreadingModel.WORKER) {
            sb.append(" worker=true");
        }
        return sb.toString();
    }

    /**
     * Builds a concise policy descriptor string for logging.
     *
     * @param policies the resolved policy annotations
     * @return a string describing active policies, or {@code "(no policies)"} if none
     */
    private String buildPolicyDesc(ResilienceAnnotations policies) {
        if (!policies.hasAny()) {
            return "(no policies)";
        }

        List<String> parts = new java.util.ArrayList<>();

        TimeoutDeclaration timeout = policies.timeout().orElse(null);
        if (timeout != null) {
            parts.add("timeout=" + timeout.unit().toMillis(timeout.value()) + "ms");
        }

        CircuitBreakerDeclaration cb = policies.circuitBreaker().orElse(null);
        if (cb != null) {
            parts.add("cb=" + cb.maxFailures() + "/" + cb.timeoutMs() + "/" + cb.resetTimeoutMs());
        }

        RetryDeclaration retry = policies.retry().orElse(null);
        if (retry != null) {
            parts.add("retry=" + retry.maxRetries() + "/" + retry.delayMs() + "ms");
        }

        return String.join(", ", parts);
    }
}
