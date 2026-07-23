// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Resolves stable service targets from durable identities or contract metadata.
 *
 * <p>The resolver provides O(1) lookups backed by the index built in
 * {@link ServiceContractRegistry} at startup. It is the single source of truth for
 * translating a stable target id (suitable for durable persistence) into a runtime
 * event bus address and full operation metadata.
 *
 * <p>Consumers that need to persist a reference to a service operation (e.g., Transactional
 * Messaging outbox rows) should store {@link ResolvedServiceTarget#targetId()} and call
 * {@link #resolve(String)} at dispatch time to obtain the current address. This decouples
 * persisted data from mutable transport details.
 *
 * <p>Instances are provided as a Dagger singleton via {@link DispatchModule}. For tests and
 * other non-Dagger contexts, use {@link #of(ServiceContractRegistry)} to create an instance.
 */
public interface ServiceTargetResolver {

    // --- Factory ---

    /**
     * Creates a {@link ServiceTargetResolver} backed by the given registry.
     *
     * <p>Prefer the Dagger-provided singleton (via {@link DispatchModule}) in production code.
     * This factory is intended for tests and non-Dagger contexts.
     *
     * @param registry the fully built service contract registry
     * @return a resolver backed by the registry
     */
    static ServiceTargetResolver of(ServiceContractRegistry registry) {
        return new DefaultServiceTargetResolver(registry);
    }

    /**
     * Resolves a service target by its stable target id.
     *
     * @param targetId the durable dot-delimited target id (e.g. {@code "integration.user-service.get-user"})
     * @return the resolved service target
     * @throws IllegalArgumentException if no operation is registered for the given target id
     */
    ResolvedServiceTarget resolve(String targetId);

    /**
     * Resolves a service target by contract interface and the reflective {@link Method}.
     *
     * <p>The operation id is read from the method's {@link ServiceOperation} annotation.
     *
     * @param contract the contract interface class
     * @param method the contract method (must carry {@link ServiceOperation})
     * @return the resolved service target
     * @throws IllegalArgumentException if the contract is not registered or the method has no registered operation
     * @throws IllegalStateException if the method is missing {@link ServiceOperation} or its value is blank
     */
    ResolvedServiceTarget resolve(Class<?> contract, Method method);

    /**
     * Resolves a service target by contract interface and durable operation id.
     *
     * @param contract the contract interface class
     * @param operationId the durable operation id (the {@link ServiceOperation#value()} of the target method)
     * @return the resolved service target
     * @throws IllegalArgumentException if the contract is not registered or the operation id is not found
     */
    ResolvedServiceTarget resolve(Class<?> contract, String operationId);

    /**
     * Returns the set of all stable target ids that this resolver can resolve.
     *
     * <p>Used by capability-aware relay claiming to determine which outbox rows
     * this node can publish.
     *
     * @return an unmodifiable set of all supported stable target ids
     */
    Set<String> supportedTargetIds();
}
