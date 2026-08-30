// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named timer resolvers to the {@link TimerResolverRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultTimerResolverRegistry}
 * construction with a mutable {@link TimerResolverRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see TimerResolverRegistry
 * @see DefaultTimerResolverRegistry
 */
@FunctionalInterface
public interface TimerResolverContributor {

    /**
     * Registers timer resolvers with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(TimerResolverRegistry.Builder builder);
}
