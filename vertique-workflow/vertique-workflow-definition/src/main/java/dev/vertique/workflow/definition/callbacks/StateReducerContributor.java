// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named state reducers to the {@link StateReducerRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultStateReducerRegistry}
 * construction with a mutable {@link StateReducerRegistry.Builder}. After all contributors run,
 * the builder is sealed and the registry becomes read-only.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see StateReducerRegistry
 * @see DefaultStateReducerRegistry
 */
@FunctionalInterface
public interface StateReducerContributor {

    /**
     * Registers state reducers with {@code builder}.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(StateReducerRegistry.Builder builder);
}
