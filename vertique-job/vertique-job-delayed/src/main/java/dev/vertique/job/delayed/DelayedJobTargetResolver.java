// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import java.util.Set;

/**
 * Resolves delayed-job targets to their durable identity and effective execution defaults.
 *
 * <p>Built at startup from the service contract registry and delayed-job configuration.
 * All lookups are O(1) at steady state.
 */
public interface DelayedJobTargetResolver {

    /**
     * Resolves a typed delayed-job contract to its target metadata.
     *
     * @param contractInterface the typed contract interface (must extend {@link DelayedJobClient})
     * @return the resolved target with effective defaults
     * @throws IllegalArgumentException if the contract is not registered
     */
    ResolvedDelayedJobTarget resolve(Class<? extends DelayedJobClient<?>> contractInterface);

    /**
     * Resolves a delayed-job target by its durable target id.
     *
     * @param targetId the durable target id (typically {@link DelayedJobContract#name()})
     * @return the resolved target with effective defaults
     * @throws IllegalArgumentException if no target is registered with the given id
     */
    ResolvedDelayedJobTarget resolve(String targetId);

    /**
     * Returns the set of all durable target ids that this resolver can resolve.
     *
     * <p>Used by capability-aware relay claiming to determine which outbox rows
     * this node can publish.
     *
     * @return an unmodifiable set of all supported durable target ids
     */
    Set<String> supportedTargetIds();
}
