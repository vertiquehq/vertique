// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * SPI for resolving a {@link SecurityIdentity} from accumulated authentication evidence and
 * request context.
 *
 * <p>Implementations participate in a chain-of-responsibility: the chain runner calls each resolver
 * in {@link OrderedExtension} order (phase, then ascending {@link #priority()}, then
 * {@link #orderKey()} which defaults to {@link #id()}), stopping at the first resolver that
 * returns a non-empty {@link Optional}. A resolver that cannot interpret the context returns
 * {@link Optional#empty()} to pass control to the next resolver in the chain.
 *
 * <p><b>Priority and id contract (NFR-ID-003):</b> Lower {@link #priority()} values run first.
 * Custom resolvers SHOULD pick stable priorities:
 * <ul>
 *   <li>{@code < 100} — run before framework defaults</li>
 *   <li>{@code > 100} — run after framework defaults</li>
 * </ul>
 * The {@link #id()} is the deterministic tie-break when two resolvers share a priority (exposed as
 * {@link #orderKey()} under the {@link OrderedExtension} contract). Two resolvers with the same
 * {@code (priority, id)} pair is a configuration error and the chain runner MUST fail loudly
 * at startup.
 *
 * <p><b>Async design:</b> {@link #resolve(SecurityIdentityResolutionContext)} returns a
 * {@link Future} so resolvers may perform network-bound work (e.g., OAuth introspection, directory
 * lookup) without blocking the Vert.x event loop.
 *
 * <p><b>Failure semantics:</b> A failed {@link Future} propagates the failure to the chain runner,
 * which MUST NOT swallow it. Only {@link Optional#empty()} signals "I cannot handle this; try the
 * next resolver".
 */
public interface SecurityIdentityResolver extends OrderedExtension {

    /**
     * Returns the priority of this resolver in the resolution chain.
     *
     * <p>Lower values run first. The default is {@code 100}. Custom resolvers SHOULD pick values
     * below {@code 100} to run before framework defaults or above {@code 100} to run after.
     *
     * @return the resolver priority; lower means earlier in the chain
     */
    @Override
    default int priority() {
        return 100;
    }

    /**
     * Returns the stable identifier used as the deterministic tie-break when two resolvers share
     * the same {@link #priority()} (NFR-ID-003).
     *
     * <p>The default is the fully-qualified class name. Custom resolvers MAY override this to pin a
     * stable id that survives class rename or refactor. Two resolvers with the same
     * {@code (priority, id)} pair is a configuration error — the chain runner MUST fail loudly
     * at startup.
     *
     * @return a non-null, non-blank stable identifier for this resolver
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Returns the {@link OrderedExtension} tie-break key for this resolver, delegating to
     * {@link #id()} so that the existing id-based tie-break is preserved under the
     * {@link OrderedExtension} contract (phase → priority → orderKey).
     *
     * @return the same value as {@link #id()}; never {@code null}
     */
    @Override
    default String orderKey() {
        return id();
    }

    /**
     * Attempts to resolve a {@link SecurityIdentity} from the given resolution context.
     *
     * <p>Returns a succeeded {@link Future} containing the resolved identity if this resolver can
     * interpret the context, or a succeeded {@link Future} containing {@link Optional#empty()} if
     * the next resolver in the chain should try. A failed {@link Future} signals an error condition
     * that MUST propagate to the caller — it is not the same as a "not handled" result.
     *
     * @param context the resolution context carrying accumulated authentication evidence, optional
     *                network origin, optional correlation, and transport-specific attributes;
     *                never {@code null}
     * @return a {@link Future} that either succeeds with the resolved identity or
     *         {@link Optional#empty()}, or fails with an error
     */
    Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context);
}
