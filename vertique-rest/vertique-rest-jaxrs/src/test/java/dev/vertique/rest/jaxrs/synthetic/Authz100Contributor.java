// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The IT's authorization contributor: records {@code authz100} into the shared {@link
 * TraceRecorder}, records the {@link SecurityPolicy} it received per operation id, and fails the
 * request with 403 when the effective policy's roles are not held. Runs at priority {@code 100}.
 */
public final class Authz100Contributor implements OperationHandlerContributor {

    private final TraceRecorder trace;
    private final Map<String, SecurityPolicy> receivedPolicies = new ConcurrentHashMap<>();

    /**
     * Creates the contributor.
     *
     * @param trace the shared trace recorder
     */
    Authz100Contributor(TraceRecorder trace) {
        this.trace = trace;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void contribute(OperationRegistrationContext context) {
        SecurityPolicy policy = context.securityPolicy();
        receivedPolicies.put(context.operationId(), policy);
        context.route().addHandler(ctx -> {
            trace.record(ctx, "authz100");
            if (policy instanceof SecurityPolicy.Constrained constrained
                    && !constrained.requiredRoles().isEmpty()
                    && Collections.disjoint(constrained.requiredRoles(), TestAuthentication.roles(ctx))) {
                ctx.fail(403);
                return;
            }
            ctx.next();
        });
    }

    /**
     * Returns the {@link SecurityPolicy} this contributor received for {@code operationId}.
     *
     * @param operationId the operation id
     * @return the received policy, or {@code null} if the operation never contributed
     */
    public SecurityPolicy receivedPolicy(String operationId) {
        return receivedPolicies.get(operationId);
    }

    @Override
    public String orderKey() {
        return "authz100";
    }
}
