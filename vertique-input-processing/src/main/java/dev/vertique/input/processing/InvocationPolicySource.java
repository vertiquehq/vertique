// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import java.util.List;
import java.util.Optional;

/**
 * One annotated element's view of one {@link PolicyAxis}, hierarchy-merged exactly as
 * {@code dev.vertique.core.util.AnnotationResolver} builds it: annotations on the declaring element
 * first, then the same declaration in each superclass bottom-up, then in each transitively
 * reachable interface, with meta-annotations (composed annotations) resolved recursively. The
 * additive annotation and the skip annotation are each resolved first-occurrence across that merged
 * view, independently of one another.
 *
 * <p>A source is a passive data carrier: it never validates itself. {@link InvocationPolicyResolver}
 * owns the conflict check between {@link #additive()} and {@link #skip()} — an implementation may
 * legitimately report both present, in which case the resolver rejects it.
 *
 * @param <V> the policy value type (e.g. {@code Class<? extends Sanitizer>})
 */
public interface InvocationPolicySource<V> {

    /**
     * The values declared by the first-occurrence additive annotation across the hierarchy-merged
     * view.
     *
     * @return the additive values, or {@link Optional#empty()} when no additive annotation is
     *     present anywhere in the merged view
     */
    Optional<List<V>> additive();

    /**
     * The declaration site of the first-occurrence additive annotation (e.g. {@code "IFoo.bar"}).
     *
     * @return the site, present if and only if {@link #additive()} is present
     */
    Optional<String> additiveDeclaredAt();

    /**
     * Whether the skip annotation is present anywhere in the hierarchy-merged view.
     *
     * @return {@code true} when a skip annotation is present
     */
    boolean skip();

    /**
     * The declaration site of the first-occurrence skip annotation.
     *
     * @return the site, present if and only if {@link #skip()} is {@code true}
     */
    Optional<String> skipDeclaredAt();

    /**
     * A human-readable description of the element this source represents, for diagnostics (e.g.
     * {@code "method FooImpl.bar"}).
     *
     * @return the element description
     */
    String describe();

    /**
     * The no-op source: no additive chain, no skip.
     *
     * @param <V> the policy value type
     * @return an {@link InvocationPolicySource} reporting no additive chain and no skip flag
     */
    static <V> InvocationPolicySource<V> none() {
        // An anonymous implementation: the interface publishes no nested type beyond its contract,
        // and the instance is stateless, so a fresh one per call costs nothing worth caching.
        return new InvocationPolicySource<>() {
            @Override
            public Optional<List<V>> additive() {
                return Optional.empty();
            }

            @Override
            public Optional<String> additiveDeclaredAt() {
                return Optional.empty();
            }

            @Override
            public boolean skip() {
                return false;
            }

            @Override
            public Optional<String> skipDeclaredAt() {
                return Optional.empty();
            }

            @Override
            public String describe() {
                return "none";
            }
        };
    }
}
