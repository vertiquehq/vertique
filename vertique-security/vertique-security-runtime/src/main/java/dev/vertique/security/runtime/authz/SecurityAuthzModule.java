// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationIntrospector;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.RolePolicyResolver;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dagger {@link Module} that wires the core authorization engine.
 *
 * <p>This module:
 * <ul>
 *   <li>Declares the empty-by-default multibinding sets for {@link ActionContributor},
 *       {@link PolicyDefinitionSource}, and {@link RolePolicyResolver} so that modules can
 *       contribute entries without the component needing to declare them.</li>
 *   <li>Binds the built-in {@link BuiltinAuthzActionContributor} {@code @IntoSet}, reserving
 *       {@code authz.action.list} and {@code authz.action.introspect} in every application.</li>
 *   <li>Provides the default in-memory {@link ActionRegistry} (built from all contributed
 *       {@link ActionContributor}s with duplicate-action detection).</li>
 *   <li>Provides a single shared {@link AuthzResolution} ({@code @Singleton}) — the merged,
 *       validated policy catalogue + merged role resolver — and builds the default
 *       {@link Authorizer} and {@link AuthorizationIntrospector} on top of that <em>same</em>
 *       instance, so the merge and validation run exactly once and the two surfaces cannot diverge.</li>
 *   <li>Provides an empty-mapping default {@link RolePolicyResolver} (bound {@code @IntoSet}) so
 *       the set is always non-empty — ensures Dagger can satisfy the injection point even when no
 *       config-backed resolver is included.</li>
 *   <li>Declares the empty-by-default multibinding set of {@link AuthorizationNarrower}s and wraps
 *       the default engine with {@link NarrowingAuthorizer} / {@link NarrowingIntrospector} so both
 *       exposed bindings fold the same ordered narrower set over the base decision — with an empty
 *       set the wrappers are behavior-identical to the base engine they wrap.</li>
 *   <li>Declares an optional {@link PrincipalAuthorityResolver} binding ({@link BindsOptionalOf}, no
 *       default) and, when an application binds one, wraps it in a {@link TimeoutPrincipalAuthorityResolver}
 *       bounded by {@link PrincipalAuthorityResolutionConfig#resolutionTimeoutMs()} — so every installed
 *       resolver is bounded, never just the ones an application remembers to wrap itself — before
 *       wrapping the narrowing-decorated {@link Authorizer} with {@link ReconstructedAuthorityResolvingAuthorizer}
 *       — the opt-in Mode-2 live re-resolution of a reconstructed context's current authority (PRD
 *       identity-002 §14.3 Phase-2 Appendix). With no resolver bound, the exposed {@link Authorizer} is
 *       unchanged from Phase 1, and {@link Vertx} / {@link PrincipalAuthorityResolutionConfig} are
 *       resolved but never used.</li>
 *   <li>Declares an optional {@link PrincipalAuthorityResolutionConfig} binding ({@link BindsOptionalOf},
 *       defaulting to {@link PrincipalAuthorityResolutionConfig#defaults()} when absent) for the Mode-2
 *       resolution timeout above — {@code PrincipalAuthorityResolutionConfigModule} is the opt-in
 *       companion that config-drives it instead of using the hardcoded default.</li>
 * </ul>
 *
 * <p><strong>Startup ordering (FR-008):</strong> the {@link ActionRegistry} is constructed first
 * (from all contributed {@link ActionContributor}s). The shared {@link #authzResolution} provider
 * then validates <em>every</em> contributed {@link PolicyDefinitionSource} against the registry
 * polymorphically (via {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)} — the core
 * never {@code instanceof}-tests a concrete source subtype), fails fast on a duplicate policy name
 * across sources, and only then materialises the catalogue. The {@link Authorizer} and
 * {@link AuthorizationIntrospector} are built last from that shared resolution. Because Dagger
 * injects {@link ActionRegistry} before calling {@link #authzResolution}, and the resolution before
 * the engine providers, this ordering is guaranteed by the dependency graph.
 *
 * <p><strong>Dependency cleanliness:</strong> this module lives in {@code vertique-security-runtime}
 * and carries no dependency on any transport, configuration, or YAML module. Config-backed
 * implementations of {@link PolicyDefinitionSource} and {@link RolePolicyResolver} live in
 * {@code vertique-security-config} and are contributed via Dagger {@code @IntoSet} multibinding.
 *
 * <p>Include this module in any Dagger {@code @Component} that wires the authorization engine.
 * Applications that use {@code JwtAuthModule} must also include this module explicitly.
 */
@Module
public abstract class SecurityAuthzModule {

    // --- multibinding declarations ---

    /**
     * Declares the empty-by-default multibinding set of {@link ActionContributor}s.
     *
     * <p>Modules contribute entries via {@code @Provides @IntoSet ActionContributor}.
     *
     * @return the set of action contributors (contains at least the built-in contributor)
     */
    @Multibinds
    abstract Set<ActionContributor> actionContributors();

    /**
     * Declares the empty-by-default multibinding set of {@link PolicyDefinitionSource}s.
     *
     * <p>Modules contribute entries via {@code @Provides @IntoSet PolicyDefinitionSource}.
     *
     * @return the set of policy definition sources (may be empty)
     */
    @Multibinds
    abstract Set<PolicyDefinitionSource> policyDefinitionSources();

    /**
     * Declares the empty-by-default multibinding set of {@link RolePolicyResolver}s.
     *
     * <p>Modules contribute entries via {@code @Provides @IntoSet RolePolicyResolver}.
     *
     * @return the set of role policy resolvers (always contains at least the empty-mapping default)
     */
    @Multibinds
    abstract Set<RolePolicyResolver> rolePolicyResolvers();

    /**
     * Declares the empty-by-default multibinding set of {@link AuthorizationNarrower}s.
     *
     * <p>Modules contribute entries via {@code @Provides @IntoSet AuthorizationNarrower}. With an
     * empty set, {@link NarrowingAuthorizer} and {@link NarrowingIntrospector} are behavior-identical
     * to the base {@link DefaultAuthorizer} / {@link DefaultAuthorizationIntrospector} they wrap.
     *
     * @return the set of authorization narrowers (may be empty)
     */
    @Multibinds
    abstract Set<AuthorizationNarrower> authorizationNarrowers();

    /**
     * Declares the optional {@link PrincipalAuthorityResolver} binding, with no framework-provided
     * default.
     *
     * <p>{@code Optional<PrincipalAuthorityResolver>} resolves to {@link Optional#empty()} unless an
     * application module binds a concrete {@link PrincipalAuthorityResolver} — that binding <em>is</em>
     * the Mode-2 opt-in (PRD identity-002 §14.3 Phase-2 Appendix). With no resolver bound, every
     * reconstructed context's current authority stays the Phase-1
     * {@link dev.vertique.security.authz.ReconstructedAuthorityMode#ATTRIBUTION_ONLY} default —
     * unchanged, byte-identical behavior. This mirrors the existing
     * {@code @BindsOptionalOf IdentitySnapshotCapture} / {@code ServiceIdentityResolver} pattern used
     * elsewhere in this module family.
     *
     * @return the optional-binding declaration (Dagger-generated; never invoked directly)
     */
    @BindsOptionalOf
    abstract PrincipalAuthorityResolver principalAuthorityResolver();

    /**
     * Declares the optional {@link PrincipalAuthorityResolutionConfig} binding, defaulting to
     * {@link PrincipalAuthorityResolutionConfig#defaults()} when absent.
     *
     * <p>Bounds every {@link PrincipalAuthorityResolver} the {@link #authorizer} provider wraps in
     * a {@link TimeoutPrincipalAuthorityResolver}. {@code PrincipalAuthorityResolutionConfigModule}
     * is the opt-in companion that config-drives this value from {@code identity.authz} instead of
     * the hardcoded default; an application may also bind {@link PrincipalAuthorityResolutionConfig}
     * programmatically some other way.
     *
     * @return the optional-binding declaration (Dagger-generated; never invoked directly)
     */
    @BindsOptionalOf
    abstract PrincipalAuthorityResolutionConfig principalAuthorityResolutionConfig();

    // --- built-in contributor ---

    /**
     * Binds the {@link BuiltinAuthzActionContributor} into the multibinding set of contributors.
     *
     * <p>This reserves {@code authz.action.list} and {@code authz.action.introspect} in every
     * application that includes this module.
     *
     * @param contributor the built-in contributor; must not be {@code null}
     * @return the contributor, to be added to the set
     */
    @Provides
    @IntoSet
    static ActionContributor builtinContributor(BuiltinAuthzActionContributor contributor) {
        return contributor;
    }

    // --- default empty role-policy resolver ---

    /**
     * Provides a default empty-mapping {@link RolePolicyResolver} bound {@code @IntoSet}.
     *
     * <p>This ensures the multibinding set of resolvers always contains at least one entry so
     * that Dagger can satisfy the {@code Set<RolePolicyResolver>} injection point even when no
     * config-backed resolver is included. Applications with a config-backed resolver contribute
     * their own entry; the wiring takes the union over all resolvers.
     *
     * @return an empty-mapping {@link RolePolicyResolver}; never {@code null}
     */
    @Provides
    @IntoSet
    static RolePolicyResolver defaultRolePolicyResolver() {
        return new InMemoryRolePolicyResolver(Map.of());
    }

    // --- ActionRegistry ---

    /**
     * Provides the {@link ActionRegistry} singleton, built from all contributed
     * {@link ActionContributor}s.
     *
     * <p>Construction is fail-fast: duplicate actions (same canonical value from two contributors)
     * throw an {@link IllegalStateException} naming both sources.
     *
     * @param contributors the set of all contributed action contributors; must not be {@code null}
     * @return the authoritative {@link ActionRegistry}; never {@code null}
     */
    @Provides
    @Singleton
    static ActionRegistry actionRegistry(Set<ActionContributor> contributors) {
        return new DefaultActionRegistry(contributors);
    }

    // --- shared resolution core ---

    /**
     * Provides the single shared {@link AuthzResolution} core ({@code @Singleton}).
     *
     * <p>This is the one place the policy catalogue is merged and validated, and it runs exactly
     * once because it is a singleton consumed by both engine providers:
     * <ol>
     *   <li><strong>Registry validation (fail-fast, polymorphic).</strong> Every contributed
     *       {@link PolicyDefinitionSource} is validated against the registry via
     *       {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)} — no {@code instanceof} on
     *       any concrete subtype, so a config-backed source is validated identically to the in-memory
     *       default.</li>
     *   <li><strong>Duplicate-name rejection (FR-018).</strong> The policies of all sources are
     *       merged; if two sources contribute a policy with the same {@link PolicyDefinition#name()}
     *       the merge fails fast with an {@link IllegalStateException} naming <em>both</em>
     *       contributing sources (mirrors {@link DefaultActionRegistry}'s duplicate-action handling).</li>
     *   <li><strong>Resolver merge.</strong> All contributed {@link RolePolicyResolver}s are merged
     *       into a composite returning the union of policy names.</li>
     * </ol>
     *
     * @param registry  the authoritative action registry; must not be {@code null}
     * @param sources   all contributed policy definition sources; must not be {@code null}
     * @param resolvers all contributed role-policy resolvers; must not be {@code null}
     * @return the shared, validated {@link AuthzResolution}; never {@code null}
     * @throws IllegalStateException if any source fails registry validation, or two sources contribute
     *     a policy with the same name
     */
    @Provides
    @Singleton
    static AuthzResolution authzResolution(
            ActionRegistry registry, Set<PolicyDefinitionSource> sources, Set<RolePolicyResolver> resolvers) {
        return new AuthzResolution(mergedSource(sources, registry), mergedResolver(resolvers));
    }

    // --- Authorizer ---

    /**
     * Provides the {@link Authorizer} singleton: the default fail-closed engine built on the shared
     * {@link AuthzResolution}, wrapped by {@link NarrowingAuthorizer} so any installed
     * {@link AuthorizationNarrower}s fold over its decisions, and — only when an application binds a
     * {@link PrincipalAuthorityResolver} — that resolver is first bounded by a {@link
     * TimeoutPrincipalAuthorityResolver} (per {@link PrincipalAuthorityResolutionConfig#resolutionTimeoutMs()},
     * defaulting when unconfigured) and then further wrapped by
     * {@link ReconstructedAuthorityResolvingAuthorizer} as the outermost decorator, activating Mode-2
     * live re-resolution of a reconstructed context's current authority (PRD identity-002 §14.3
     * Phase-2 Appendix). With an empty narrower set and no resolver bound, the exposed binding is
     * behavior-identical to the base {@link DefaultAuthorizer} — reconstructed contexts stay
     * {@link dev.vertique.security.authz.ReconstructedAuthorityMode#ATTRIBUTION_ONLY} (Phase-1
     * behavior, byte-identical), and {@code vertx}/{@code resolutionConfig} are resolved but unused.
     *
     * @param registry        the authoritative action registry; must not be {@code null}
     * @param resolution      the shared, validated resolution core; must not be {@code null}
     * @param narrowers       the contributed narrowers; must not be {@code null}
     * @param resolver        the optional application-supplied {@link PrincipalAuthorityResolver};
     *                        empty when no application binding is present, in which case Mode-2
     *                        stays inactive
     * @param resolutionConfig the optional Mode-2 resolution-timeout configuration; empty defaults
     *                         to {@link PrincipalAuthorityResolutionConfig#defaults()}
     * @param vertx           the {@link Vertx} instance used to bound every wrapped resolver call
     *                        with a timeout; must not be {@code null}
     * @return the fully-decorated {@link Authorizer}; never {@code null}
     */
    @Provides
    @Singleton
    static Authorizer authorizer(
            ActionRegistry registry,
            AuthzResolution resolution,
            Set<AuthorizationNarrower> narrowers,
            Optional<PrincipalAuthorityResolver> resolver,
            Optional<PrincipalAuthorityResolutionConfig> resolutionConfig,
            Vertx vertx) {
        Authorizer base = new DefaultAuthorizer(registry, resolution);
        Authorizer narrowing = new NarrowingAuthorizer(base, narrowers);
        return resolver.<Authorizer>map(r -> {
                    long timeoutMs = resolutionConfig
                            .orElseGet(PrincipalAuthorityResolutionConfig::defaults)
                            .resolutionTimeoutMs();
                    PrincipalAuthorityResolver bounded = new TimeoutPrincipalAuthorityResolver(r, vertx, timeoutMs);
                    return new ReconstructedAuthorityResolvingAuthorizer(narrowing, bounded);
                })
                .orElse(narrowing);
    }

    // --- AuthorizationIntrospector ---

    /**
     * Provides the {@link AuthorizationIntrospector} singleton: the default in-memory engine built
     * on the <em>same</em> shared {@link AuthzResolution} as the {@link Authorizer} (so the
     * introspector's set-valued answer and the authorizer's single-action verdict are guaranteed to
     * agree, AC-22), wrapped by {@link NarrowingIntrospector} so any installed
     * {@link AuthorizationNarrower}s annotate its capabilities. With an empty narrower set the
     * exposed binding is behavior-identical to the base {@link DefaultAuthorizationIntrospector}.
     *
     * @param registry   the authoritative action registry; must not be {@code null}
     * @param resolution the shared, validated resolution core; must not be {@code null}
     * @param narrowers  the contributed narrowers; must not be {@code null}
     * @return the {@link NarrowingIntrospector}-wrapped default engine; never {@code null}
     */
    @Provides
    @Singleton
    static AuthorizationIntrospector authorizationIntrospector(
            ActionRegistry registry, AuthzResolution resolution, Set<AuthorizationNarrower> narrowers) {
        AuthorizationIntrospector base = new DefaultAuthorizationIntrospector(registry, resolution);
        return new NarrowingIntrospector(base, narrowers);
    }

    // --- internal helpers ---

    /**
     * Validates every contributed source against the registry, then merges their policies into one
     * source, failing fast on a duplicate policy name across sources.
     *
     * <p>Validation is polymorphic ({@link PolicyDefinitionSource#validateAgainst(ActionRegistry)});
     * the core never depends on a concrete source subtype. Duplicate {@link PolicyDefinition#name()}
     * values across two sources are a startup error naming both sources (FR-018), rather than the
     * silent last-wins that the downstream catalogue index would otherwise apply.
     *
     * @param sources  the contributed sources; must not be {@code null}
     * @param registry the authoritative registry used for startup validation; must not be
     *                 {@code null}
     * @return a merged {@link PolicyDefinitionSource} with globally-unique policy names; never
     *     {@code null}
     * @throws IllegalStateException if any source fails registry validation, or two sources contribute
     *     a policy with the same name
     */
    private static PolicyDefinitionSource mergedSource(Set<PolicyDefinitionSource> sources, ActionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        List<PolicyDefinition> merged = new ArrayList<>();
        // Tracks which source class declared each policy name, so a duplicate can name both offenders.
        Map<String, String> sourceByName = new LinkedHashMap<>();
        for (PolicyDefinitionSource source : sources) {
            // Fail-fast registry validation for every source (no instanceof on concrete subtypes).
            source.validateAgainst(registry);
            String sourceClass = source.getClass().getName();
            for (PolicyDefinition policy : source.policies()) {
                String existingSource = sourceByName.putIfAbsent(policy.name(), sourceClass);
                if (existingSource != null) {
                    throw new IllegalStateException("duplicate policy name \"" + policy.name()
                            + "\" contributed by both " + existingSource + " and " + sourceClass);
                }
                merged.add(policy);
            }
        }
        List<PolicyDefinition> immutable = List.copyOf(merged);
        return () -> immutable;
    }

    /**
     * Merges all contributed {@link RolePolicyResolver}s into one that returns the union of
     * policy names from every constituent resolver for the given roles.
     *
     * @param resolvers the contributed resolvers; must not be {@code null}
     * @return a composite {@link RolePolicyResolver}; never {@code null}
     */
    private static RolePolicyResolver mergedResolver(Set<RolePolicyResolver> resolvers) {
        return roles -> {
            Objects.requireNonNull(roles, "roles");
            return resolvers.stream()
                    .flatMap(r -> r.policiesForRoles(roles).stream())
                    .collect(Collectors.toUnmodifiableSet());
        };
    }
}
