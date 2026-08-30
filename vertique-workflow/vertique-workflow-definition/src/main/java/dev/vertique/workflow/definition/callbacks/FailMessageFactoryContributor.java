// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named fail-message factories to the
 * {@link FailMessageFactoryRegistry} at startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultFailMessageFactoryRegistry}
 * construction with a mutable {@link FailMessageFactoryRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see FailMessageFactoryRegistry
 * @see DefaultFailMessageFactoryRegistry
 */
@FunctionalInterface
public interface FailMessageFactoryContributor {

    /**
     * Registers fail-message factories with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(FailMessageFactoryRegistry.Builder builder);
}
