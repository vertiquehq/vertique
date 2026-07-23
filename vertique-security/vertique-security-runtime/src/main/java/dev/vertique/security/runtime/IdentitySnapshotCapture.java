// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.IdentitySnapshotFactory;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import java.util.Objects;

/**
 * Producer-side gate that captures the currently authenticated identity as an
 * {@link IdentitySnapshotContext} and binds it into the {@link ContextHolder}, honored only when
 * the global {@code identity.snapshot.captureEnabled} kill-switch is on (PRD-ID-002 §14.3
 * "Durable carriage", §15 A3 ingress capture seam).
 *
 * <p>{@link #captureFrom(SecurityContext)} is the ingress seam the REST identity middleware calls:
 * it captures the credential-free {@link IdentitySnapshotContent} from the live context via the
 * injected {@link IdentitySnapshotFactory} and binds it for the request's remaining lifetime,
 * returning the bind {@link ContextHolder.Scope} so the caller can unwind it with the request. When the
 * kill-switch is off it short-circuits to {@link ContextScopes#noop()} <em>without</em> consulting
 * the factory, so the no-capture hot path pays nothing — and no durable-boundary encoder (e.g.
 * {@link IdentitySnapshotDurableEncoder}) ever sees a snapshot to carry.
 *
 * <p>An eligibility gate also skips capture for an unauthenticated live context — see
 * {@link #captureFrom(SecurityContext)} (PRD-ID-002 Phase-1 review W3).
 *
 * <p>Provided as a {@code @Provides @Singleton} binding by {@link IdentitySnapshotCarriageModule}
 * (not an {@code @Inject} constructor), so {@code rest-security}'s
 * {@code @BindsOptionalOf IdentitySnapshotCapture} resolves to {@link java.util.Optional#empty()}
 * unless carriage is installed — mirroring the {@link IdentitySnapshotDegradationPolicy} optional.
 * Dagger forbids {@code @BindsOptionalOf} of an unqualified {@code @Inject} type (it is always
 * present), which is why capture is a module-provided binding.
 */
public final class IdentitySnapshotCapture {

    private final ContextHolder holder;
    private final IdentitySnapshotFactory factory;
    private final boolean captureEnabled;

    /**
     * Constructs a capture gate over the given holder and factory, honoring the given kill-switch
     * state.
     *
     * @param holder         the context holder to bind an {@link IdentitySnapshotContext} into;
     *                       must not be {@code null}
     * @param factory        the credential-free snapshot factory used by
     *                       {@link #captureFrom(SecurityContext)}; must not be {@code null}
     * @param captureEnabled the resolved {@code identity.snapshot.captureEnabled} kill-switch
     *                       state; when {@code false}, {@link #captureFrom(SecurityContext)} is a
     *                       no-op
     */
    public IdentitySnapshotCapture(ContextHolder holder, IdentitySnapshotFactory factory, boolean captureEnabled) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.captureEnabled = captureEnabled;
    }

    /**
     * Captures a snapshot of the given live security context and binds it as an
     * {@link IdentitySnapshotContext} for the current scope, returning the bind
     * {@link ContextHolder.Scope} so the caller can unwind it (e.g. with the request lifecycle).
     *
     * <p>The kill-switch is evaluated <strong>first</strong>: when capture is disabled this returns
     * the shared {@link ContextScopes#noop()} scope without building a snapshot, so a disabled
     * capture costs nothing beyond the branch. When capture is enabled, an eligibility gate then
     * checks the live context: an anonymous actor ({@link PrincipalType#ANONYMOUS}) or a
     * NONE-kind primary authentication method also short-circuits to {@link ContextScopes#noop()}
     * without consulting the factory, since an unauthenticated identity carries no attribution
     * worth persisting.
     *
     * @param live the live security context to snapshot; must not be {@code null}
     * @return the bind scope restoring the prior {@link IdentitySnapshotContext} binding on close,
     *         or {@link ContextScopes#noop()} when capture is disabled or the live context is
     *         unauthenticated
     */
    public ContextHolder.Scope captureFrom(SecurityContext live) {
        Objects.requireNonNull(live, "live");
        if (!captureEnabled) {
            return ContextScopes.noop();
        }
        if (isUnauthenticated(live)) {
            return ContextScopes.noop();
        }
        IdentitySnapshotContent content = factory.capture(live);
        return holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.of(content));
    }

    /**
     * Returns {@code true} when the live context carries no attribution worth persisting — an
     * anonymous actor ({@link PrincipalType#ANONYMOUS}) or a primary authentication method that
     * normalizes to {@link AuthMethodKind#NONE}. Capturing such a context anyway would snapshot an
     * identity with false provenance.
     *
     * @param live the live security context to test
     * @return whether the context is ineligible for snapshot capture
     */
    private static boolean isUnauthenticated(SecurityContext live) {
        return live.identity().actor().type() == PrincipalType.ANONYMOUS
                || live.authentication().primaryMethod().normalizedKind() == AuthMethodKind.NONE;
    }
}
