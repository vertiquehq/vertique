// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import dev.vertique.examples.services.codegen.policy.PolicyFixtures.ActionOnlyPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.AuthenticatedPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.BothScopesPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.DenyPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.OperatorAndActionPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.OperatorPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.PermitPolicy;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract of the direct-implementation pattern: the typed policies are declared on the
 * contract interface and the implementation carries no security declaration of its own.
 */
@ServiceContract(namespace = "policy", value = "direct")
public interface PolicyService {

    /**
     * Performs work that declares no security requirement.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("unrestricted")
    Future<String> unrestricted(String resourceId);

    /**
     * Performs work any caller may request.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("permit")
    @RequiresPolicy(PermitPolicy.class)
    Future<String> permit(String resourceId);

    /**
     * Performs work no caller may request.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("deny")
    @RequiresPolicy(DenyPolicy.class)
    Future<String> deny(String resourceId);

    /**
     * Performs work any authenticated caller may request.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("authenticated")
    @RequiresPolicy(AuthenticatedPolicy.class)
    Future<String> authenticated(String resourceId);

    /**
     * Performs work only an operator may request.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("operator")
    @RequiresPolicy(OperatorPolicy.class)
    Future<String> operator(String resourceId);

    /**
     * Performs work that needs both the read and the write scope.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("scoped")
    @RequiresPolicy(BothScopesPolicy.class)
    Future<String> scoped(String resourceId);

    /**
     * Performs work guarded only by the registered action.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("action")
    @RequiresPolicy(ActionOnlyPolicy.class)
    Future<String> action(String resourceId);

    /**
     * Performs work that needs the operator role and then the registered action.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result
     */
    @ServiceOperation("operatorAction")
    @RequiresPolicy(OperatorAndActionPolicy.class)
    Future<String> operatorAction(String resourceId);

    /**
     * Performs operator work on a resource the application must find eligible before the effect.
     *
     * @param resourceId the resource the caller acts on
     * @return the work result, or a failure when the resource is not eligible
     */
    @ServiceOperation("owned")
    @RequiresPolicy(OperatorPolicy.class)
    Future<String> owned(String resourceId);
}
