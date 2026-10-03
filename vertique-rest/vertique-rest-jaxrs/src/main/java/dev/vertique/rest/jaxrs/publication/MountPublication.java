// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * INTERNAL: one JAX-RS mount's detached publication, handed to every {@link
 * MountPublicationHook} at the end of router creation. Public only for cross-module use by
 * sibling framework modules; outside the maturity promise and not an application contract.
 *
 * @param mountPath       the mount path as registered (e.g. {@code "/api/public/*"})
 * @param mountId         the mount's identifier
 * @param applicationName the declared application's name, for a mount serving a declared
 *                        application; {@code null} for every other mount. Non-{@code null} exactly
 *                        when {@code declaringType} is non-{@code null}
 * @param declaringType   the declared application's declaring interface, for a mount serving a
 *                        declared application; {@code null} for every other mount. Non-{@code null}
 *                        exactly when {@code applicationName} is non-{@code null}
 * @param strategyId      the configured request-validation strategy id, even for an empty mount
 * @param operations      every operation registered on this mount, in registration order
 */
public record MountPublication(
        String mountPath,
        String mountId,
        @Nullable String applicationName,
        @Nullable Class<?> declaringType,
        String strategyId,
        List<OperationPublication> operations) {

    /**
     * Compact constructor storing an unmodifiable copy of {@code operations}.
     */
    public MountPublication {
        operations = List.copyOf(operations);
    }
}
