// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named branch-result reducers to the
 * {@link BranchResultReducerRegistry} at startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultBranchResultReducerRegistry}
 * construction with a mutable {@link BranchResultReducerRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see BranchResultReducerRegistry
 * @see DefaultBranchResultReducerRegistry
 */
@FunctionalInterface
public interface BranchResultReducerContributor {

    /**
     * Registers branch-result reducers with {@code builder}.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(BranchResultReducerRegistry.Builder builder);
}
