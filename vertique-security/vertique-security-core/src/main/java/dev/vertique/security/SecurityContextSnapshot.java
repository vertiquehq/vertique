// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.origin.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable point-in-time snapshot of the security facts an async audit observer needs.
 *
 * <p>A {@link SecurityContext} is bound to a Vert.x {@code ContextLocal} and is accessible for
 * the lifetime of a single request. Async pipelines (e.g. audit emission, event-bus propagation)
 * need to hold security facts beyond the request lifecycle — after the {@code ContextLocal} may
 * be rebound for a different request. {@code SecurityContextSnapshot} pins the three fact records
 * that the default audit path reads, so a later holder rebind cannot swap them out from under an
 * off-thread projection.
 *
 * <p>The fields the default audit path reads — principal ids/types, auth method, assurance
 * scalars, and origin — are themselves immutable records. The free-form
 * {@code Map<String, Object>} attribute maps inside {@link SecurityIdentity#actor()} (via
 * {@link PrincipalRef#attributes()}) and {@link AuthenticationState#safeAttributes()} are
 * <em>not</em> deep-frozen. The outer map reference is stable (unmodifiable copy made at record
 * construction time), but if a value in the map is itself a mutable collection the snapshot does
 * not protect against modification of that value. A custom resolver or projector reading such
 * attribute-map values off-thread must treat them as read-only.
 *
 * <p>Authorization claims ({@link SecurityContext#authorization()}) are intentionally excluded —
 * the default audit path never reads them, and their inclusion would grow snapshot cost for no
 * benefit in the common case.
 *
 * <p>Use {@link #from(SecurityContext)} to capture a snapshot from a live context.
 *
 * @param identity       the security identity pinned at snapshot time; never {@code null}
 * @param authentication the authentication state pinned at snapshot time; never {@code null}
 * @param origin         the optional network-envelope snapshot; {@code Optional.empty()} when
 *                       origin capture is not configured or the context carried no origin
 */
public record SecurityContextSnapshot(
        SecurityIdentity identity, AuthenticationState authentication, Optional<RequestOrigin> origin) {

    /**
     * Compact constructor — validates required fields and normalises a {@code null} origin to
     * {@link Optional#empty()}.
     *
     * @param identity       the security identity; must not be {@code null}
     * @param authentication the authentication state; must not be {@code null}
     * @param origin         the optional origin; {@code null} is treated as
     *                       {@link Optional#empty()}
     * @throws NullPointerException if {@code identity} or {@code authentication} is {@code null}
     */
    public SecurityContextSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(authentication, "authentication");
        origin = (origin == null) ? Optional.empty() : origin;
    }

    // --- static factories ---

    /**
     * Captures a point-in-time snapshot of the security facts from the given
     * {@link SecurityContext}.
     *
     * <p>The returned snapshot holds the same object references as the context at the moment of
     * the call. The context's {@code ContextLocal} binding can be rebound after this call without
     * affecting the snapshot.
     *
     * @param context the live security context to snapshot; must not be {@code null}
     * @return a new snapshot carrying the context's identity, authentication, and origin
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public static SecurityContextSnapshot from(SecurityContext context) {
        Objects.requireNonNull(context, "context");
        return new SecurityContextSnapshot(context.identity(), context.authentication(), context.origin());
    }
}
