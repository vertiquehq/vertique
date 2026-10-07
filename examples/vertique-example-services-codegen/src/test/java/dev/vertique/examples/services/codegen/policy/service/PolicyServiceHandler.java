// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import dev.vertique.examples.services.codegen.policy.PolicyFixtures;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Effects;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Eligibility;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.NotEligibleException;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handler-pattern implementation of {@link PolicyHandlerService}. Every body counts one business
 * effect and observes the caller through the injected {@link SecurityContext} parameter.
 */
@Singleton
public class PolicyServiceHandler implements ServiceHandler<PolicyHandlerService> {

    private static final String PATTERN = "handler";

    private final Effects effects;
    private final Eligibility eligibility;

    /**
     * Creates the handler.
     *
     * @param effects     the business effect log
     * @param eligibility the application-owned eligibility check
     */
    @Inject
    public PolicyServiceHandler(Effects effects, Eligibility eligibility) {
        this.effects = effects;
        this.eligibility = eligibility;
    }

    /**
     * Handles the operation without a security declaration.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> unrestricted(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_UNRESTRICTED, resourceId, caller);
    }

    /**
     * Handles the permit-everyone operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> permit(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_PERMIT, resourceId, caller);
    }

    /**
     * Handles the deny-everyone operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> deny(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_DENY, resourceId, caller);
    }

    /**
     * Handles the authenticated-only operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> authenticated(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_AUTHENTICATED, resourceId, caller);
    }

    /**
     * Handles the operator-only operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> operator(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_OPERATOR, resourceId, caller);
    }

    /**
     * Handles the two-scope operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> scoped(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_SCOPED, resourceId, caller);
    }

    /**
     * Handles the action-only operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> action(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_ACTION, resourceId, caller);
    }

    /**
     * Handles the operator-and-action operation.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result
     */
    public Future<String> operatorAction(String resourceId, SecurityContext caller) {
        return perform(PolicyFixtures.OP_OPERATOR_ACTION, resourceId, caller);
    }

    /**
     * Handles the operator operation on an application-owned resource.
     *
     * @param resourceId the resource the caller acts on
     * @param caller     the propagated caller, or {@code null}
     * @return the work result, or a failure when the resource is not eligible
     */
    public Future<String> owned(String resourceId, SecurityContext caller) {
        if (!eligibility.isEligible(resourceId)) {
            return Future.failedFuture(new NotEligibleException(resourceId));
        }
        return perform(PolicyFixtures.OP_OWNED, resourceId, caller);
    }

    private Future<String> perform(String operation, String resourceId, SecurityContext caller) {
        String actorId = caller == null ? "none" : caller.identity().actor().id();
        effects.record(PATTERN, operation, actorId);
        return Future.succeededFuture(PATTERN + ":" + operation + ":" + resourceId);
    }
}
