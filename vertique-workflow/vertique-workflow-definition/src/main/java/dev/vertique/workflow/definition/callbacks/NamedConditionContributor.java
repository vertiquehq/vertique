// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named conditions to the {@link NamedConditionRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultNamedConditionRegistry}
 * construction with a mutable {@link NamedConditionRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see NamedConditionRegistry
 * @see DefaultNamedConditionRegistry
 */
@FunctionalInterface
public interface NamedConditionContributor {

    /**
     * Registers named conditions with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(NamedConditionRegistry.Builder builder);
}
