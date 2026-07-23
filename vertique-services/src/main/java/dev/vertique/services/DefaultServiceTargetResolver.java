// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.services.dispatch.ServiceMethodMeta;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link ServiceTargetResolver} implementation backed by {@link ServiceContractRegistry}.
 *
 * <p>Builds three lookup maps at construction time from the registry data:
 * <ol>
 *   <li>A {@code targetId → ServiceMethodMeta} map, sourced directly from
 *       {@link ServiceContractRegistry#targetIndex()} (non-null stable target ids only).</li>
 *   <li>A {@code contract → ContractEntry} map that mirrors the registry for
 *       contract+operationId lookups.</li>
 *   <li>A {@code targetId → Class<?>} map for reverse lookup of the contract class from a
 *       stable target id, enabling the {@link ResolvedServiceTarget#contract()} field to be
 *       populated in the single-argument {@link #resolve(String)} path.</li>
 * </ol>
 *
 * <p>All {@link ServiceTargetResolver} resolution methods perform O(1) lookups at
 * steady state with no reflection or registry traversal at call time.
 *
 * <p>Wired by {@link DispatchModule} as a Dagger singleton.
 */
class DefaultServiceTargetResolver implements ServiceTargetResolver {

    /** Lookup map from stable target id to operation metadata. */
    private final Map<String, ServiceMethodMeta> targetIndex;

    /** Lookup map from contract class to its registry entry, for contract+operationId lookups. */
    private final Map<Class<?>, ServiceContractRegistry.ContractEntry<?>> contractIndex;

    /** Lookup map from stable target id to the contract class that owns the operation. */
    private final Map<String, Class<?>> targetContracts;

    /**
     * Constructs the resolver from the given registry.
     *
     * @param registry the fully built service contract registry
     */
    DefaultServiceTargetResolver(ServiceContractRegistry registry) {
        this.targetIndex = Map.copyOf(registry.targetIndex());

        var entries = registry.entries();
        Map<Class<?>, ServiceContractRegistry.ContractEntry<?>> idx = new HashMap<>(entries.size() + 1);
        Map<String, Class<?>> contracts = new HashMap<>();
        for (ServiceContractRegistry.ContractEntry<?> entry : entries) {
            idx.put(entry.contract(), entry);
            for (ServiceMethodMeta meta : entry.operations().values()) {
                String targetId = meta.stableTargetId();
                if (targetId != null) {
                    contracts.put(targetId, entry.contract());
                }
            }
        }
        this.contractIndex = Map.copyOf(idx);
        this.targetContracts = Map.copyOf(contracts);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if no operation is registered for the given target id
     */
    @Override
    public ResolvedServiceTarget resolve(String targetId) {
        ServiceMethodMeta meta = targetIndex.get(targetId);
        if (meta == null) {
            throw new IllegalArgumentException("No service operation registered for stable target id: " + targetId);
        }
        Class<?> contract = targetContracts.get(targetId);
        return toResolved(targetId, contract, meta);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the contract is not registered, the method has no entry,
     *     or the method is not annotated with {@link ServiceOperation}
     * @throws IllegalStateException if {@link ServiceOperation} is present but its value is blank
     */
    @Override
    public ResolvedServiceTarget resolve(Class<?> contract, Method method) {
        String operationId = OperationIdResolver.resolveStableOperationId(method);
        if (operationId == null) {
            throw new IllegalArgumentException("Method '" + method.getName() + "' on "
                    + contract.getSimpleName()
                    + " is not annotated with @ServiceOperation — only explicitly annotated"
                    + " operations are eligible for stable-target resolution");
        }
        return resolve(contract, operationId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the contract is not registered or the operation id is not found
     */
    @Override
    public ResolvedServiceTarget resolve(Class<?> contract, String operationId) {
        ServiceContractRegistry.ContractEntry<?> entry = contractIndex.get(contract);
        if (entry == null) {
            throw new IllegalArgumentException("No service registered for contract: " + contract.getName());
        }
        ServiceMethodMeta meta = entry.operations().get(operationId);
        if (meta == null) {
            throw new IllegalArgumentException(
                    "No operation '" + operationId + "' registered on contract: " + contract.getName());
        }
        String targetId = meta.stableTargetId();
        if (targetId == null) {
            throw new IllegalArgumentException("Operation '" + operationId + "' on " + contract.getName()
                    + " has no stable target id (non-service entry)");
        }
        return toResolved(targetId, contract, meta);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The returned set is backed by the immutable {@code targetIndex} key set built at
     * construction time; no copy is made.
     */
    @Override
    public Set<String> supportedTargetIds() {
        return targetIndex.keySet();
    }

    /**
     * Builds a {@link ResolvedServiceTarget} from a stable target id, contract class, and metadata.
     *
     * @param targetId the stable target id
     * @param contract the contract interface class that declares this operation; may be {@code null}
     *                 if the target id has no associated contract in the registry
     * @param meta the operation metadata
     * @return the resolved service target
     */
    private static ResolvedServiceTarget toResolved(String targetId, Class<?> contract, ServiceMethodMeta meta) {
        return new ResolvedServiceTarget(
                targetId, contract, meta.namespace(), meta.name(), meta.operation(), meta, meta.address());
    }
}
