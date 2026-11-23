// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named output mappers to the {@link OutputMapperRegistry}
 * at startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultOutputMapperRegistry}
 * construction with a mutable {@link OutputMapperRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see OutputMapperRegistry
 * @see DefaultOutputMapperRegistry
 */
@FunctionalInterface
public interface OutputMapperContributor {

    /**
     * Registers output mappers with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(OutputMapperRegistry.Builder builder);
}
