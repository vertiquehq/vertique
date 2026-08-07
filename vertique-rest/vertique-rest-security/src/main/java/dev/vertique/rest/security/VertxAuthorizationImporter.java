// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.context.WarningThrottle;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.Authorizations;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.auth.authorization.WildcardPermissionBasedAuthorization;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Imports authorizations granted by registered Vert.x {@link AuthorizationProvider}s into the
 * framework's typed {@link AuthorizationClaims} model.
 *
 * <p><strong>Execution contract</strong> — the following is the frozen behavior of this importer;
 * callers and provider authors may rely on every clause:
 *
 * <ul>
 *   <li><strong>Opt-in.</strong> The importer is never active by default. It is installed only when
 *       the application wires the Vert.x authorization-import module
 *       ({@link VertxAuthorizationImportModule}); with no such wiring, no Vert.x authorization
 *       provider is ever consulted and {@link AuthorizationClaims} keeps whatever the identity
 *       pipeline already produced.</li>
 *   <li><strong>Sequential, deterministic invocation.</strong> Providers run one at a time, in
 *       ascending {@link AuthorizationProvider#getId() provider id} order, on the calling Vert.x
 *       context. Provider <em>N+1</em> is not invoked until provider <em>N</em>'s future has
 *       completed, so provider ordering — and therefore the observable claim set — does not depend
 *       on registration order or on scheduling luck.</li>
 *   <li><strong>Request-local user.</strong> Providers never see the caller's {@link User}. Exactly
 *       one request-local {@code User} is built per import from deep copies of the caller's
 *       principal and attributes ({@link JsonObject#copy()}, {@code null} mapping to an empty
 *       object) and is shared by every provider in the chain. The caller's principal and attributes
 *       are read <em>only</em> to create those copies and are never mutated or handed out; the
 *       caller's {@link User#authorizations() authorizations} are neither read nor written. This is
 *       deliberate: Vert.x does not guarantee that {@link User#create(JsonObject, JsonObject)}
 *       defensively copies its arguments, so the copy is made here.</li>
 *   <li><strong>No importer-level timeout (v1).</strong> Providers must not block the event loop
 *       and own their own timeouts. A provider whose future never completes stalls that request's
 *       authorization indefinitely — the importer adds no watchdog.</li>
 *   <li><strong>All-or-nothing publication.</strong> Claims are derived and merged only after
 *       <em>every</em> provider has succeeded. Any provider failure — a failed future or a
 *       synchronous throw — short-circuits the chain and fails the whole import with
 *       {@link UnavailableException}, naming the offending provider id and carrying its cause. No
 *       partially imported claim is ever observable.</li>
 *   <li><strong>Fail-closed mapping.</strong> Only resource-free {@link RoleBasedAuthorization} and
 *       {@link PermissionBasedAuthorization} grants with a non-blank value map to an
 *       {@link AuthorityClaim}. Everything else — wildcard permissions, {@code And}/{@code Or}/
 *       {@code Not} composites, resource-scoped grants, blank values, and unknown authorization
 *       types — is dropped and logged once per {@code (provider, authorization type)} pair at WARN
 *       through a {@link WarningThrottle}. The warning names the provider and the authorization
 *       type, never the authorization value.</li>
 *   <li><strong>Provenance and merge.</strong> Imported claims carry
 *       {@code source = "vertx-provider:<providerId>"} and are merged into the base claims by plain
 *       set union under full record equality, preserving the base claims' attributes. A base claim
 *       that differs only in {@code source} therefore survives alongside its imported twin.</li>
 *   <li><strong>Excluded providers.</strong> Providers whose id is excluded are neither invoked nor
 *       read. The safe single-argument constructor always excludes
 *       {@value #EXCLUDED_JWT_CLAIMS_PROVIDER_ID}, whose bucket is a lossy re-projection of JWT
 *       claims already handled by the identity pipeline. When no provider remains after exclusion,
 *       the base claims instance is returned unchanged on an already-completed future — no async
 *       hop, no allocation.</li>
 * </ul>
 *
 * @see AuthorizationProvider
 * @see AuthorizationClaims
 */
@Slf4j
public final class VertxAuthorizationImporter {

    /**
     * Provider id of the Vert.x {@code jwt-claims} authorization bucket, excluded by default.
     *
     * <p>That bucket is a lossy re-projection of JWT claims the identity pipeline already maps with
     * full issuer/audience provenance; importing it would duplicate claims and strip that
     * provenance.
     */
    static final String EXCLUDED_JWT_CLAIMS_PROVIDER_ID = "jwt-claims";

    /** Prefix of the {@link AuthorityClaim#source()} value stamped on every imported claim. */
    private static final String SOURCE_PREFIX = "vertx-provider:";

    /** Non-excluded providers, sorted by ascending {@link AuthorizationProvider#getId()}. */
    private final List<AuthorizationProvider> orderedProviders;

    /** Once-per-{@code (provider, authorization type)} WARN throttle for dropped authorizations. */
    private final WarningThrottle dropWarnings = new WarningThrottle();

    // --- Construction ---

    /**
     * Creates an importer over the given providers with the default exclusion set — the safe
     * constructor, which always excludes {@value #EXCLUDED_JWT_CLAIMS_PROVIDER_ID}.
     *
     * @param providers the registered Vert.x authorization providers; must not be {@code null} and
     *                  must not contain {@code null} elements
     * @throws IllegalStateException if any provider exposes a {@code null} or blank id, or if two
     *                               providers share the same id
     */
    public VertxAuthorizationImporter(Set<AuthorizationProvider> providers) {
        this(providers, Set.of(EXCLUDED_JWT_CLAIMS_PROVIDER_ID));
    }

    /**
     * Creates an importer over the given providers with an explicit exclusion set.
     *
     * <p>Validates the whole provider set eagerly, so a misconfigured provider graph fails at wiring
     * time rather than on the first authenticated request: every provider id must be non-{@code
     * null}, non-blank, and unique across the set. Both inputs are defensively copied; the retained
     * providers are the non-excluded ones, sorted by ascending id.
     *
     * @param providers          the registered Vert.x authorization providers; must not be
     *                           {@code null} and must not contain {@code null} elements
     * @param excludedProviderIds ids of providers that must be neither invoked nor read; must not be
     *                           {@code null}
     * @throws IllegalStateException if any provider exposes a {@code null} or blank id (the message
     *                               names the offending provider class), or if two providers share
     *                               the same id (the message names both provider classes)
     */
    VertxAuthorizationImporter(Set<AuthorizationProvider> providers, Set<String> excludedProviderIds) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(excludedProviderIds, "excludedProviderIds");
        Set<String> excluded = Set.copyOf(excludedProviderIds);

        // Validate ids before sorting: a null id would break the comparator, and a duplicate id
        // would silently make one provider's bucket unreadable.
        Map<String, AuthorizationProvider> byId = new LinkedHashMap<>();
        for (AuthorizationProvider provider : Set.copyOf(providers)) {
            String id = provider.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("AuthorizationProvider "
                        + provider.getClass().getName()
                        + " exposes a null or blank id; every authorization provider must expose a stable, "
                        + "non-blank id");
            }
            AuthorizationProvider existing = byId.putIfAbsent(id, provider);
            if (existing != null) {
                throw new IllegalStateException("Duplicate AuthorizationProvider id ["
                        + id
                        + "] between "
                        + existing.getClass().getName()
                        + " and "
                        + provider.getClass().getName());
            }
        }

        this.orderedProviders = byId.entrySet().stream()
                .filter(entry -> !excluded.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    // --- Import ---

    /**
     * Runs the provider chain for the given user and merges the resulting claims into {@code base}.
     *
     * <p>See the class javadoc for the full execution contract. In short: providers run sequentially
     * in ascending id order against a request-local deep copy of {@code user}; the merged claims are
     * published only once every provider has succeeded; any provider failure fails the returned
     * future with {@link UnavailableException}.
     *
     * @param user the authenticated caller's user; must not be {@code null}. Never mutated, never
     *             exposed to providers
     * @param base the claims resolved so far by the identity pipeline; must not be {@code null}. Its
     *             attributes are carried over unchanged
     * @return a future completing with the union of {@code base}'s claims and the imported claims;
     *         the very same {@code base} instance on an already-completed future when no provider
     *         remains after exclusion; failed with {@link UnavailableException} when any provider
     *         fails
     * @throws NullPointerException if {@code user} or {@code base} is {@code null}
     */
    public Future<AuthorizationClaims> importInto(User user, AuthorizationClaims base) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(base, "base");
        if (orderedProviders.isEmpty()) {
            return Future.succeededFuture(base);
        }

        // One request-local user for the whole chain. The caller's User is not touched again.
        User localUser = User.create(copyOrEmpty(user.principal()), copyOrEmpty(user.attributes()));

        Future<Void> chain = Future.succeededFuture();
        for (AuthorizationProvider provider : orderedProviders) {
            chain = chain.compose(ignored -> invoke(provider, localUser));
        }
        return chain.map(ignored -> merge(base, collectClaims(localUser)));
    }

    // --- Private helpers ---

    /**
     * Deep-copies a Vert.x JSON object, mapping {@code null} to an empty object.
     *
     * @param source the object to copy; may be {@code null}
     * @return an independent copy, or a fresh empty object when {@code source} is {@code null}
     */
    private static JsonObject copyOrEmpty(JsonObject source) {
        return source == null ? new JsonObject() : source.copy();
    }

    /**
     * Invokes one provider against the request-local user, normalizing every failure mode — a failed
     * future, a synchronous throw, or a {@code null} future — into a failed future carrying an
     * {@link UnavailableException} that names the provider.
     *
     * @param provider  the provider to invoke; must not be {@code null}
     * @param localUser the request-local user the provider may read and mutate; must not be
     *                  {@code null}
     * @return a future completing when the provider has finished, or failed with
     *         {@link UnavailableException}
     */
    private static Future<Void> invoke(AuthorizationProvider provider, User localUser) {
        String providerId = provider.getId();
        try {
            Future<Void> result = provider.getAuthorizations(localUser);
            if (result == null) {
                return Future.failedFuture(
                        unavailable(providerId, new IllegalStateException("getAuthorizations returned a null future")));
            }
            return result.recover(cause -> Future.failedFuture(unavailable(providerId, cause)));
        } catch (RuntimeException e) {
            return Future.failedFuture(unavailable(providerId, e));
        }
    }

    /**
     * Builds the failure raised when a provider cannot resolve its authorizations.
     *
     * @param providerId the failing provider's id
     * @param cause      the underlying failure
     * @return an {@link UnavailableException} naming the provider and carrying {@code cause}
     */
    private static UnavailableException unavailable(String providerId, Throwable cause) {
        return new UnavailableException(
                "Vert.x authorization provider [" + providerId + "] failed to resolve authorizations", cause);
    }

    /**
     * Reads every non-excluded provider's bucket off the request-local user and maps it to claims,
     * dropping (and throttled-WARNing) whatever cannot be represented as an {@link AuthorityClaim}.
     *
     * @param localUser the request-local user the providers populated; must not be {@code null}
     * @return the imported claims, in ascending provider-id order
     */
    private Set<AuthorityClaim> collectClaims(User localUser) {
        Authorizations authorizations = localUser.authorizations();
        Set<AuthorityClaim> imported = new LinkedHashSet<>();
        for (AuthorizationProvider provider : orderedProviders) {
            String providerId = provider.getId();
            if (!authorizations.contains(providerId)) {
                continue;
            }
            authorizations.forEach(providerId, authorization -> map(providerId, authorization)
                    .ifPresentOrElse(imported::add, () -> warnDropped(providerId, authorization)));
        }
        return imported;
    }

    /**
     * Maps a single Vert.x authorization to an {@link AuthorityClaim}, or to nothing when it is not
     * representable.
     *
     * <p>Wildcard permissions are tested first and always dropped: their glob semantics cannot be
     * expressed by an exact-match claim value, so importing one would silently widen authority.
     * Resource-scoped grants are dropped for the same fail-closed reason — {@link AuthorityClaim}
     * has no resource dimension, so keeping the value alone would strip the scope that narrows it.
     *
     * @param providerId    the id of the provider that granted the authorization
     * @param authorization the authorization to map; must not be {@code null}
     * @return the mapped claim, or {@link Optional#empty()} when the authorization must be dropped
     */
    private static Optional<AuthorityClaim> map(String providerId, Authorization authorization) {
        if (authorization instanceof WildcardPermissionBasedAuthorization) {
            return Optional.empty();
        }
        if (authorization instanceof RoleBasedAuthorization role
                && role.getResource() == null
                && isNotBlank(role.getRole())) {
            return Optional.of(claim(AuthorityKind.ROLE, role.getRole(), providerId));
        }
        if (authorization instanceof PermissionBasedAuthorization permission
                && permission.getResource() == null
                && isNotBlank(permission.getPermission())) {
            return Optional.of(claim(AuthorityKind.PERMISSION, permission.getPermission(), providerId));
        }
        return Optional.empty();
    }

    /**
     * Builds an imported claim with {@code vertx-provider:<id>} provenance and no issuer, audience,
     * or attributes — a Vert.x authorization provider carries none of those.
     *
     * @param kind       the authority category
     * @param value      the non-blank authority value
     * @param providerId the granting provider's id
     * @return the imported claim
     */
    private static AuthorityClaim claim(AuthorityKind kind, String value, String providerId) {
        return new AuthorityClaim(kind, value, "", "", SOURCE_PREFIX + providerId, Map.of());
    }

    /**
     * Logs, at most once per {@code (provider, authorization type)} pair, that an authorization was
     * dropped. The authorization <em>value</em> is deliberately never logged — it may carry
     * tenant-identifying or otherwise sensitive data.
     *
     * @param providerId    the id of the provider that granted the dropped authorization
     * @param authorization the dropped authorization
     */
    private void warnDropped(String providerId, Authorization authorization) {
        String type = authorization.getClass().getSimpleName();
        dropWarnings.once(
                "authz-import|" + providerId + "|" + type,
                key -> log.warn(
                        "Dropping unmappable Vert.x authorization of type [{}] from provider [{}]; only "
                                + "resource-free role and permission authorizations are imported (fail-closed). "
                                + "Authorization values are never logged.",
                        type,
                        providerId));
    }

    /**
     * Unions the imported claims into the base claims, keeping the base attributes unchanged.
     *
     * @param base     the claims resolved by the identity pipeline
     * @param imported the claims derived from the provider chain
     * @return the merged claims
     */
    private static AuthorizationClaims merge(AuthorizationClaims base, Set<AuthorityClaim> imported) {
        Set<AuthorityClaim> merged = new LinkedHashSet<>(base.claims());
        merged.addAll(imported);
        return new AuthorizationClaims(merged, base.attributes());
    }

    /**
     * Null-safe blank check.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when {@code value} is non-{@code null} and contains a non-whitespace
     *         character
     */
    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
