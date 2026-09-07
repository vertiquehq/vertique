// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import java.util.List;

/**
 * Resolves invocation-level canonicalization/sanitization chains from passive
 * {@link InvocationPolicySource} views, honoring the route and parameter precedence chains and
 * rejecting a source that declares both an additive chain and a skip flag on the same
 * {@link PolicyAxis}.
 *
 * <p>This class is transport-neutral: it operates entirely on {@link InvocationPolicySource}, never
 * on reflection or annotations directly. {@link ReflectiveInvocationPolicies} is the reflective
 * adapter that builds sources from real classes and methods.
 */
public final class InvocationPolicyResolver {

    private InvocationPolicyResolver() {}

    /**
     * Resolves the route-level chain: method-skip &gt; method-additive &gt; type-skip &gt;
     * type-additive &gt; none.
     *
     * @param method the method-level source
     * @param type   the type-level source
     * @param axis   the axis being resolved
     * @param <V>    the policy value type
     * @return the resolved route chain
     * @throws InvocationPolicyConflictException when {@code method} or {@code type} declares both
     *     additive and skip
     */
    public static <V> List<V> resolveRouteChain(
            InvocationPolicySource<V> method, InvocationPolicySource<V> type, PolicyAxis axis) {
        requireNoConflict(axis, method);
        requireNoConflict(axis, type);

        if (method.skip()) {
            return List.of();
        }
        if (method.additive().isPresent()) {
            return method.additive().get();
        }
        if (type.skip()) {
            return List.of();
        }
        return type.additive().orElseGet(List::of);
    }

    /**
     * Resolves the parameter-level chain: param-skip &gt; param-additive &gt; {@code routeChain}.
     *
     * @param parameter  the parameter-level source
     * @param routeChain the already-resolved route chain to fall back to
     * @param axis       the axis being resolved
     * @param <V>        the policy value type
     * @return the resolved parameter chain
     * @throws InvocationPolicyConflictException when {@code parameter} declares both additive and
     *     skip
     */
    public static <V> List<V> resolveParameterChain(
            InvocationPolicySource<V> parameter, List<V> routeChain, PolicyAxis axis) {
        requireNoConflict(axis, parameter);

        if (parameter.skip()) {
            return List.of();
        }
        return parameter.additive().orElse(routeChain);
    }

    private static <V> void requireNoConflict(PolicyAxis axis, InvocationPolicySource<V> source) {
        if (source.skip() && source.additive().isPresent()) {
            throw new InvocationPolicyConflictException(
                    axis,
                    source.describe(),
                    source.additiveDeclaredAt().orElseThrow(),
                    source.skipDeclaredAt().orElseThrow());
        }
    }
}
