// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.services.dispatch.ServiceMethodMeta;

/**
 * The result of a {@link ServiceTargetResolver} lookup for a single service operation.
 *
 * <p>Carries the durable stable target id alongside the resolved routing coordinates so
 * callers can read either the durable identity (for persistence) or the runtime address
 * (for event bus dispatch) from a single object.
 *
 * @param targetId  the stable dot-delimited target id (e.g. {@code "integration.user-service.get-user"})
 * @param contract  the contract interface class that declares this operation
 * @param namespace the service namespace segment (empty string when absent)
 * @param name      the service name segment
 * @param operation the durable operation id
 * @param meta      the full operation metadata, including resilience annotations and parameter descriptors
 * @param address   the runtime event bus address derived from {@code namespace}, {@code name}, and {@code operation}
 */
public record ResolvedServiceTarget(
        String targetId,
        Class<?> contract,
        String namespace,
        String name,
        String operation,
        ServiceMethodMeta meta,
        String address) {

    // --- Factory Methods ---

    /**
     * Constructs a {@link ResolvedServiceTarget} from a contract class and operation metadata.
     *
     * <p>Convenience factory for use in {@link ServiceClientFactory} proxy dispatch, where the
     * contract is already known and the metadata carries all routing coordinates. The
     * {@code targetId} is taken from {@link ServiceMethodMeta#stableTargetId()} and may be
     * {@code null} for non-service entries.
     *
     * @param contract the contract interface class that declares the operation
     * @param meta     the operation metadata
     * @return the resolved target for this contract and operation
     */
    public static ResolvedServiceTarget of(Class<?> contract, ServiceMethodMeta meta) {
        return new ResolvedServiceTarget(
                meta.stableTargetId(), contract, meta.namespace(), meta.name(), meta.operation(), meta, meta.address());
    }
}
