// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.AuthenticationEvidence;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * REST-layer helper for accumulating and reading {@link AuthenticationEvidence} entries on a
 * {@link RoutingContext}.
 *
 * <p>Encapsulates the well-known routing-context storage key so callers never manipulate the key
 * directly. The design is additive: multiple auth handlers (JWT wrapper, mTLS handler,
 * application-provided custom handlers) each call {@link #append(RoutingContext,
 * AuthenticationEvidence)} with their own verified evidence entry. The downstream
 * {@link IdentityResolutionMiddleware} reads the accumulated list via
 * {@link #get(RoutingContext)} and feeds it into the {@link
 * dev.vertique.security.resolver.SecurityIdentityResolutionContext}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Pre-auth: no entries on the context.</li>
 *   <li>Auth handler success: framework handler wrappers (JWT, etc.) and application handlers
 *       call {@link #append} to record their verified credentials.</li>
 *   <li>Post-auth: {@code IdentityResolutionMiddleware} calls {@link #get} to snapshot the
 *       complete list before resolving the {@link dev.vertique.security.SecurityIdentity}.</li>
 * </ol>
 *
 * <p>This class is intentionally non-instantiable; all methods are static.
 *
 * @see dev.vertique.security.AuthenticationEvidence
 */
public final class RestAuthenticationEvidence {

    /** Well-known routing-context key. Stable across the request lifecycle. */
    private static final String KEY = RestAuthenticationEvidence.class.getName() + ".evidence";

    private RestAuthenticationEvidence() {
        // non-instantiable
    }

    /**
     * Appends an {@link AuthenticationEvidence} entry to the routing context. Creates the
     * underlying mutable collector on the first call; subsequent calls simply add to it.
     *
     * <p>Entries are accumulated in insertion order. A single request with layered authentication
     * (e.g., mTLS plus JWT) produces one entry per verified credential.
     *
     * @param ctx      the current routing context; must not be {@code null}
     * @param evidence the verified evidence to record; must not be {@code null}
     * @throws NullPointerException if {@code ctx} or {@code evidence} is {@code null}
     */
    public static void append(RoutingContext ctx, AuthenticationEvidence evidence) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(evidence, "evidence");
        @SuppressWarnings("unchecked")
        List<AuthenticationEvidence> list = ctx.get(KEY);
        if (list == null) {
            list = new ArrayList<>();
            ctx.put(KEY, list);
        }
        list.add(evidence);
    }

    /**
     * Returns an immutable snapshot of the accumulated {@link AuthenticationEvidence} entries in
     * insertion order. Returns an empty list if no evidence has been appended.
     *
     * <p>The returned list is a defensive copy; subsequent {@link #append} calls do not mutate it.
     *
     * @param ctx the current routing context; must not be {@code null}
     * @return immutable snapshot of accumulated evidence entries; never {@code null}
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public static List<AuthenticationEvidence> get(RoutingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        @SuppressWarnings("unchecked")
        List<AuthenticationEvidence> list = ctx.get(KEY);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Resets the routing context to its pre-authentication (no-evidence) state by discarding any
     * accumulated evidence collector. After this call {@link #get(RoutingContext)} returns an empty
     * list until a subsequent {@link #append(RoutingContext, AuthenticationEvidence)}.
     *
     * <p>This is used at a trust boundary that must bind an identity <em>without consulting ambient
     * evidence</em> — specifically the MCP mount's no-scheme canonical-anonymous path (§4.7 stage 3),
     * where any evidence appended by ambient Router handlers ahead of the mount must not bleed into
     * the resolved anonymous identity or its primary authentication method. It is a no-op when no
     * evidence has been appended.
     *
     * @param ctx the current routing context; must not be {@code null}
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public static void clear(RoutingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        ctx.remove(KEY);
    }
}
