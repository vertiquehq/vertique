// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.core.extension.OrderedExtension;

/**
 * Extension point for contributing handlers to individual OpenAPI operation routes.
 *
 * <p>Contributors are invoked during route registration for each discovered operation,
 * after the route is resolved but before the {@code ResourceMethodInvoker} is added.
 * This allows modules to add per-operation handlers (authorization, context bridging,
 * assurance checks, etc.) with deterministic ordering.
 *
 * <p>Contributors are sorted by the {@link OrderedExtension} ordering contract — phase first,
 * then {@link #priority()} ascending, then {@link #orderKey()} as a stable tie-break.
 * The operation invoker is always added last, after all contributors have run.
 *
 * <p>Unlike the other {@link OrderedExtension} sub-interfaces, {@link #priority()} is
 * <em>abstract</em> here — every contributor must explicitly choose a priority band.
 * The default from {@link OrderedExtension} is intentionally suppressed.
 *
 * <p>Contributed via Dagger {@code Set<OperationHandlerContributor>} multibinding.
 *
 * <p>This replaces ad-hoc handler insertion in {@code RouterLifecycleHook.afterAuthSetup()}
 * and provides a first-class extension mechanism for operation-chain handler composition.
 *
 * <p>Contributors may also run for framework-owned <em>synthetic</em> operations: routes installed
 * outside normal resource-method discovery. A synthetic route runs the same contributor chain, with
 * the same inputs, as an equally-secured resource route: the same contributors, in the same order,
 * with the same effective security policy. A synthetic operation's id lives in the reserved {@code
 * apidocs:} namespace, its {@link OperationRegistrationContext#operation() descriptor} reports a
 * literal route template with no consumed or produced media types, and the descriptor's annotations
 * are the synthetic security annotations its effective policy was built from — so a contributor that
 * reads annotations sees exactly what an equally annotated resource method would show. No signature,
 * default, or behavior of this interface changes for a synthetic operation.
 *
 * <p>A synthetic route renders a failure from its status alone ({@code ctx.fail(status)} or an
 * {@code HttpException}), without the application's exception mapping or interceptors; a contributor
 * rejecting a synthetic operation fails with an explicit 4xx or 5xx status.
 *
 * @see OperationRegistrationContext
 * @see dev.vertique.rest.core.lifecycle.RouterLifecycleHook
 * @see OrderedExtension
 */
public interface OperationHandlerContributor extends OrderedExtension {

    /**
     * Priority for ordering. Lower values execute first.
     * The operation invoker ({@code ResourceMethodInvoker}) is always added after all contributors.
     *
     * <p>Recommended ranges (with where the framework's own contributors sit):
     * <ul>
     *   <li>0–99: Pre-authorization — claims validation and identity resolution / SecurityContext
     *       <em>binding</em> (the framework binds the {@code SecurityContext} here:
     *       {@code JwtClaimsValidatorContributor} at 50, {@code IdentityResolutionContributor} at 80),
     *       plus rate limiting and request decoration</li>
     *   <li>100–199: Authorization — role/scope checks ({@code AuthorizationContributor} at 100)</li>
     *   <li>200–299: Post-authorization context bridging — e.g. exposing the already-bound
     *       {@code SecurityContext} to JAX-RS {@code @Context} injection (the context is bound earlier,
     *       in the pre-authorization band, not here)</li>
     *   <li>300+: Post-context — auditing, tracing</li>
     * </ul>
     *
     * @return the priority value; must be explicitly provided by every implementor
     */
    @Override
    int priority();

    /**
     * Contributes handlers to a single OpenAPI operation route.
     *
     * <p>Called once per operation during route registration. Implementations typically
     * inspect {@link OperationRegistrationContext#operationId()} and
     * {@link OperationRegistrationContext#securityPolicy()} for security annotations or
     * other metadata, then call {@code context.route().addHandler(...)} to add handlers.
     * Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param context the registration context providing operation metadata and the target route
     */
    void contribute(OperationRegistrationContext context);
}
