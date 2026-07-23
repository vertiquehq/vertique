// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named subject resolvers to the {@link SubjectResolverRegistry}
 * at startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultSubjectResolverRegistry}
 * construction with a mutable {@link SubjectResolverRegistry.Builder}.
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see SubjectResolverRegistry
 * @see DefaultSubjectResolverRegistry
 */
@FunctionalInterface
public interface SubjectResolverContributor {

    /**
     * Registers subject resolvers with {@code builder}.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(SubjectResolverRegistry.Builder builder);
}
