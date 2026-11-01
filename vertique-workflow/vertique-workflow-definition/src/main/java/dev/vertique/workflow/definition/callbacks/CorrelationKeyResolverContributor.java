// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named correlation-key resolvers to the {@link CorrelationKeyResolverRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultCorrelationKeyResolverRegistry}
 * construction with a mutable {@link CorrelationKeyResolverRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see CorrelationKeyResolverRegistry
 * @see DefaultCorrelationKeyResolverRegistry
 */
@FunctionalInterface
public interface CorrelationKeyResolverContributor {

    /**
     * Registers correlation-key resolvers with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(CorrelationKeyResolverRegistry.Builder builder);
}
