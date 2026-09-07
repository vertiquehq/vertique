// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves invocation-level canonicalization/sanitization chains from passive
 * {@link InvocationPolicySource} views, honoring the route and parameter precedence chains and
 * rejecting a source that declares both an additive chain and a skip flag on the same
 * {@link PolicyAxis}.
 *
 * <p>This class is transport-neutral: it operates entirely on {@link InvocationPolicySource}, never
 * on reflection or annotations directly. {@link ReflectiveInvocationPolicies} is the reflective
 * adapter that builds sources from real classes and methods.
 *
 * <h2>Inherited-skip warning</h2>
 *
 * <p>The precedence below lets a skip declared on a <em>supertype</em> site win over a non-empty
 * chain declared one level closer to the invocation — an interface method's
 * {@code @SkipSanitization} over the implementing class's {@code @Sanitize(X)}, say. That is not a
 * conflict: the two annotations sit on different elements, and method-skip legitimately beats
 * type-additive. But nothing on the element the reader is looking at says the chain will not run, so
 * the resolver logs one {@code WARN} whenever it drops a non-empty chain because of a skip the
 * element only inherited. The warning is defence in depth — it changes no resolved value, and every
 * scenario resolves to exactly the chain it resolved to before.
 *
 * <p>Under annotation processing the warning reaches the build log; at runtime it reaches the
 * application log at registration (endpoint scanning), never per request.
 *
 * <p><b>Own-site convention.</b> Whether a skip is inherited is decided without any addition to
 * {@link InvocationPolicySource}: both adapters render a declaration site as
 * {@code "<DeclaringType>.<member>"} ({@code "IFoo.bar"}) and an element description as a kind
 * followed by that same form for the element's own site ({@code "method FooImpl.bar"},
 * {@code "parameter 0 of method FooImpl.bar"}). So
 * {@code describe().endsWith(" " + skipDeclaredAt())} holds exactly when the skip sits on the
 * element's own site — see {@code ReflectiveInvocationPolicies} (describe strings at L102 and
 * L141-142, site name at L204) and {@code apt.ElementInvocationPolicies} (describe strings at L115
 * and L166, site name at L536). An adapter that renders sites differently loses the warning, never
 * correctness.
 */
public final class InvocationPolicyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(InvocationPolicyResolver.class);

    /**
     * The additive-site placeholder used when a parameter's inherited skip removes the
     * already-resolved route chain: a route chain is a plain {@code List}, so it carries no
     * declaration site of its own.
     */
    private static final String ROUTE_CHAIN_SITE = "the route";

    private InvocationPolicyResolver() {}

    /**
     * Resolves the route-level chain: method-skip &gt; method-additive &gt; type-skip &gt;
     * type-additive &gt; none.
     *
     * <p>Logs one {@code WARN} when {@code method}'s skip removes a non-empty chain declared by
     * {@code type} and that skip is inherited rather than declared on the method itself; see the
     * class documentation.
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
            warnIfInheritedSkipRemovesChain(
                    axis,
                    method,
                    type.additive().orElse(List.of()),
                    type.additiveDeclaredAt().orElse(null));
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
     * <p>Logs one {@code WARN} when {@code parameter}'s skip removes a non-empty {@code routeChain}
     * and that skip is inherited rather than declared on the parameter itself; see the class
     * documentation.
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
            warnIfInheritedSkipRemovesChain(axis, parameter, routeChain, ROUTE_CHAIN_SITE);
            return List.of();
        }
        return parameter.additive().orElse(routeChain);
    }

    /**
     * Logs the inherited-skip warning when {@code source}'s winning skip drops a non-empty chain the
     * element itself never opted out of.
     *
     * @param axis         the axis being resolved
     * @param source       the element-level source whose skip won
     * @param removedChain the chain the skip removes; nothing is logged when it is empty
     * @param removedFrom  the removed chain's declaration site, or {@link #ROUTE_CHAIN_SITE}
     * @param <V>          the policy value type
     */
    private static <V> void warnIfInheritedSkipRemovesChain(
            PolicyAxis axis, InvocationPolicySource<V> source, List<V> removedChain, String removedFrom) {
        if (removedChain.isEmpty() || removedFrom == null || !skipIsInherited(source)) {
            return;
        }
        LOG.warn(
                "{} declared on {} removes the {} chain declared on {} for {} — the override inherits the skip;"
                        + " declare the chain on the element or remove the inherited skip",
                axis.skipAnnotation(),
                source.skipDeclaredAt().orElseThrow(),
                axis.additiveAnnotation(),
                removedFrom,
                source.describe());
    }

    /**
     * Whether {@code source}'s skip annotation sits on a site other than the element's own, per the
     * own-site convention documented on this class.
     *
     * @param source the element-level source to inspect
     * @param <V>    the policy value type
     * @return {@code true} when the skip is inherited from a supertype site
     */
    private static <V> boolean skipIsInherited(InvocationPolicySource<V> source) {
        String site = source.skipDeclaredAt().orElse(null);
        return site != null && !source.describe().endsWith(" " + site);
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
