// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.job.delayed.config.DelayedJobContractConfig;
import dev.vertique.services.ServiceContractRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link DelayedJobTargetResolver}.
 *
 * <p>Built at startup from the {@link ServiceContractRegistry} (entries with
 * {@code type == "delayed-job"}) and {@link DelayedJobHandlerRegistrar} (for handler addresses),
 * combined with delayed-job configuration to resolve effective execution defaults.
 *
 * <p>Effective defaults follow the same precedence as {@link DelayedJobClientProxy}:
 * config {@code delayedJob.contracts.{name}.*} &gt; {@link DelayedJobContract} annotation &gt;
 * framework defaults.
 *
 * <p>Fails fast on duplicate target IDs at construction time.
 */
@Slf4j
public class DefaultDelayedJobTargetResolver implements DelayedJobTargetResolver {

    // --- State ---

    private final Map<String, ResolvedDelayedJobTarget> byTargetId;
    private final Map<Class<?>, ResolvedDelayedJobTarget> byContractClass;

    // --- Constructor ---

    /**
     * Builds the resolver from the registry, registrar, and typed per-contract override index.
     *
     * @param registry        the service contract registry providing delayed-job contract entries
     * @param registrar       the handler registrar providing runtime event bus addresses
     * @param contractConfigs the typed per-contract override index (keyed by contract name), parsed
     *                        and validated at the {@code DelayedJobModule} boundary; the resolver
     *                        never sees the raw root config
     * @throws IllegalStateException if duplicate target IDs are detected
     */
    public DefaultDelayedJobTargetResolver(
            ServiceContractRegistry registry,
            DelayedJobHandlerRegistrar registrar,
            Map<String, DelayedJobContractConfig> contractConfigs) {
        Map<String, ResolvedDelayedJobTarget> byId = new HashMap<>();
        Map<Class<?>, ResolvedDelayedJobTarget> byClass = new HashMap<>();
        List<String> violations = new ArrayList<>();

        Map<String, String> handlerAddresses = registrar.handlerAddresses();

        for (ServiceContractRegistry.ContractEntry<?> entry : registry.entries()) {
            if (!"delayed-job".equals(entry.namespace())) {
                continue;
            }

            // Resolve the contract interface (C = second type arg of DelayedJobExecutor<P, C>).
            // The contributor stores the executor class as the contract key; use TypeResolver
            // to extract the concrete C, mirroring DelayedJobContractContributor.buildEntry().
            Class<?> contractInterface =
                    TypeResolver.resolveTypeArgument(entry.contract(), DelayedJobExecutor.class, 1);
            if (contractInterface == null) {
                log.warn(
                        "Skipping delayed-job entry '{}': could not resolve @DelayedJobContract interface from {}",
                        entry.name(),
                        entry.contract().getName());
                continue;
            }

            DelayedJobContract annotation = contractInterface.getAnnotation(DelayedJobContract.class);
            if (annotation == null) {
                log.warn(
                        "Skipping delayed-job entry '{}': contract interface {} lacks @DelayedJobContract",
                        entry.name(),
                        contractInterface.getName());
                continue;
            }

            String name = annotation.name();
            DelayedJobContractConfig contractConfig = contractConfigs.get(name);

            // Config overrides win per field; an absent (null) override falls back to the annotation
            // value — the same absent-vs-set precedence the raw-JsonObject lookup provided.
            int effectiveMaxAttempts = contractConfig != null && contractConfig.maxAttempts() != null
                    ? contractConfig.maxAttempts()
                    : annotation.maxAttempts();
            String effectiveQueue = contractConfig != null && contractConfig.queue() != null
                    ? contractConfig.queue()
                    : annotation.queue();
            int effectivePriority = contractConfig != null && contractConfig.priority() != null
                    ? contractConfig.priority()
                    : annotation.priority();

            String handlerAddress = handlerAddresses.getOrDefault(name, "jobs/delayed/" + name + "/execute");

            ResolvedDelayedJobTarget target = new ResolvedDelayedJobTarget(
                    name, name, handlerAddress, effectiveQueue, effectivePriority, effectiveMaxAttempts);

            if (byId.containsKey(name)) {
                violations.add("Duplicate delayed-job target id '" + name + "'");
                continue;
            }

            byId.put(name, target);
            byClass.put(contractInterface, target);

            log.debug(
                    "Resolved delayed-job target '{}' → address={}, queue={}, priority={}, maxAttempts={}",
                    name,
                    handlerAddress,
                    effectiveQueue,
                    effectivePriority,
                    effectiveMaxAttempts);
        }

        // --- Phase 2: Add annotation-based handlers (@DelayedJobHandlerMethod) ---
        // These have no @DelayedJobContract annotation, so they use framework defaults only.
        // They are not resolvable by contract class (no typed contract interface).
        for (Map.Entry<String, String> handlerEntry : handlerAddresses.entrySet()) {
            String handlerName = handlerEntry.getKey();
            if (byId.containsKey(handlerName)) {
                // Already registered as a typed contract — skip (typed contract wins)
                continue;
            }
            String handlerAddress = handlerEntry.getValue();
            ResolvedDelayedJobTarget target = new ResolvedDelayedJobTarget(
                    handlerName,
                    handlerName,
                    handlerAddress,
                    "default", // framework default queue
                    0, // framework default priority
                    3); // framework default maxAttempts
            byId.put(handlerName, target);
            log.debug(
                    "Resolved annotation-based delayed-job target '{}' → address={} (framework defaults)",
                    handlerName,
                    handlerAddress);
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Delayed-job target resolution failed with " + violations.size()
                    + " violation(s):\n" + String.join("\n", violations));
        }

        this.byTargetId = Map.copyOf(byId);
        this.byContractClass = Map.copyOf(byClass);
    }

    // --- DelayedJobTargetResolver ---

    /** {@inheritDoc} */
    @Override
    public Set<String> supportedTargetIds() {
        return byTargetId.keySet();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the contract is not registered
     */
    @Override
    public ResolvedDelayedJobTarget resolve(Class<? extends DelayedJobClient<?>> contractInterface) {
        ResolvedDelayedJobTarget target = byContractClass.get(contractInterface);
        if (target == null) {
            throw new IllegalArgumentException(
                    "No delayed-job target registered for contract interface: " + contractInterface.getName());
        }
        return target;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if no target is registered with the given id
     */
    @Override
    public ResolvedDelayedJobTarget resolve(String targetId) {
        ResolvedDelayedJobTarget target = byTargetId.get(targetId);
        if (target == null) {
            throw new IllegalArgumentException("No delayed-job target registered with id: " + targetId);
        }
        return target;
    }
}
