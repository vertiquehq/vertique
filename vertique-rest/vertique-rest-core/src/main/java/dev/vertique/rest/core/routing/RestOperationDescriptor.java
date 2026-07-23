// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.EffectiveSecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/**
 * Transport-neutral description of a single REST operation, exposing only members that reference
 * core-visible types.
 *
 * <p>This is the base descriptor consumed by rest-core SPIs (route registration, security-policy
 * validation, audit, and content-type validation) without coupling rest-core to rest-jaxrs. The
 * richer JAX-RS/schema view extends this base in rest-jaxrs.
 *
 * <p>The identity fields ({@link #operationId()}, {@link #httpMethod()}, {@link #routeTemplate()})
 * are never {@code null}. The collection accessors return immutable, possibly empty lists, never
 * {@code null}. Custom contributors read the annotation accessors to branch on application
 * annotations.
 */
public interface RestOperationDescriptor {

    /**
     * Returns the operation identifier — the {@code @Operation(operationId)} value when present,
     * otherwise the resource method name. Duplicate-validated at startup.
     *
     * @return the non-null operation identifier
     */
    String operationId();

    /**
     * Returns the HTTP method for this operation.
     *
     * @return the non-null HTTP method name, e.g. {@code "GET"}
     */
    String httpMethod();

    /**
     * Returns the route template for this operation.
     *
     * @return the non-null route template, e.g. {@code "/users/{id}"}
     */
    String routeTemplate();

    /**
     * Returns the declared {@code @Consumes} media types, used for 415 content-type validation.
     *
     * @return the non-null, possibly empty list of consumed media types
     */
    List<String> consumes();

    /**
     * Returns the declared {@code @Produces} media types, used for Accept negotiation.
     *
     * @return the non-null, possibly empty list of produced media types
     */
    List<String> produces();

    /**
     * Returns the security policy resolved from the operation's authorization annotations
     * ({@code @RolesAllowed}, {@code @PermitAll}, {@code @DenyAll}, {@code @Authorized}).
     *
     * @return the non-null security policy
     */
    SecurityPolicy securityPolicy();

    /**
     * Returns the effective security requirement sets, resolved operation-level-else-global, modelled
     * as <strong>OR-of-AND</strong>.
     *
     * <p>The outer list is the <strong>OR</strong> set of alternatives: a request that satisfies
     * <em>any one</em> {@link SecurityRequirementSet} is authorized, matching the OpenAPI
     * {@code security}-array semantics. Each set is an <strong>AND</strong>-group of schemes (all
     * must be satisfied), and the scopes within a scheme are themselves an AND. An empty outer list
     * means the operation is public.
     *
     * <p>Swagger-core maps each {@code @SecurityRequirement} annotation to a separate
     * {@code security}-array entry, so from annotations every alternative is a single-scheme set and
     * the operation's sets are an OR of single schemes. Multi-scheme sets
     * (AND-within-a-set) arise from {@code @SecurityRequirement(combine=…)} and the opt-in
     * {@code openapi-contract} strategy via the loaded contract.
     *
     * @return the non-null, possibly empty list of security requirement sets (the OR set of
     *     alternatives)
     */
    List<SecurityRequirementSet> securityRequirementSets();

    /**
     * Returns the resolved method-level annotations — the resource method plus any overridden and
     * interface-declared methods.
     *
     * @return the non-null, possibly empty list of method annotations
     */
    List<Annotation> methodAnnotations();

    /**
     * Returns the resolved class-level annotations — the resource class plus its superclasses and
     * interfaces.
     *
     * @return the non-null, possibly empty list of class annotations
     */
    List<Annotation> classAnnotations();

    /**
     * Finds an annotation of the given type, searching method-level annotations first and then
     * class-level annotations.
     *
     * @param type the annotation type to look up
     * @param <A>  the annotation type
     * @return the annotation wrapped in an {@link Optional}, or {@link Optional#empty()} if absent
     */
    <A extends Annotation> Optional<A> findAnnotation(Class<A> type);

    /**
     * Returns the operation's <strong>effective</strong> security policy — the base
     * {@link #securityPolicy()} with a single-scheme {@link SecurityRequirementSet}'s scopes folded in
     * via {@link EffectiveSecurityPolicy#fold} — after first enforcing ADR-0124's fail-closed matrix
     * via {@link EffectiveSecurityPolicy#enforceSupportedShape}.
     *
     * <p>This makes the effective policy a property of the operation rather than something each caller
     * recomputes: the route registrar and the security-policy validator read <em>one</em> consistent
     * effective policy. Because this method runs {@code enforceSupportedShape} first, simply
     * <em>accessing</em> the effective policy is the always-on fail-closed gate — it throws for a
     * multi-scheme set, a scoped-OR, or the both-scopes shape independently of whether the optional
     * {@code DefaultSecurityPolicyValidator} is wired.
     *
     * @return the operation's effective security policy with set scopes folded in; never {@code null}
     * @throws RestConfigurationException if the operation declares a security shape whose V1
     *     enforcement is deferred (multi-scheme set, scoped-OR, or both-scopes); see
     *     {@link EffectiveSecurityPolicy#enforceSupportedShape}
     */
    default SecurityPolicy effectiveSecurityPolicy() {
        EffectiveSecurityPolicy.enforceSupportedShape(operationId(), securityRequirementSets(), securityPolicy());
        return EffectiveSecurityPolicy.fold(securityPolicy(), securityRequirementSets());
    }
}
