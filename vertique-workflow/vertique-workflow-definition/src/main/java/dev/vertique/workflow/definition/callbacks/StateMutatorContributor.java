// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named state mutators to the {@link StateMutatorRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultStateMutatorRegistry}
 * construction with a mutable {@link StateMutatorRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see StateMutatorRegistry
 * @see DefaultStateMutatorRegistry
 */
@FunctionalInterface
public interface StateMutatorContributor {

    /**
     * Registers state mutators with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(StateMutatorRegistry.Builder builder);
}
