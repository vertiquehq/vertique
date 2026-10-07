// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import dev.vertique.context.ContextValues;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Effects;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Eligibility;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.NotEligibleException;
import dev.vertique.security.SecurityContext;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Direct implementation of {@link PolicyService}. Every body counts one business effect, so a
 * denied dispatch is visible as an effect count that stays at zero.
 */
@Singleton
public class PolicyServiceImpl implements PolicyService {

    private static final String PATTERN = "direct";

    private final Effects effects;
    private final Eligibility eligibility;

    /**
     * Creates the implementation.
     *
     * @param effects     the business effect log
     * @param eligibility the application-owned eligibility check
     */
    @Inject
    public PolicyServiceImpl(Effects effects, Eligibility eligibility) {
        this.effects = effects;
        this.eligibility = eligibility;
    }

    @Override
    public Future<String> unrestricted(String resourceId) {
        return perform(PolicyFixtures.OP_UNRESTRICTED, resourceId);
    }

    @Override
    public Future<String> permit(String resourceId) {
        return perform(PolicyFixtures.OP_PERMIT, resourceId);
    }

    @Override
    public Future<String> deny(String resourceId) {
        return perform(PolicyFixtures.OP_DENY, resourceId);
    }

    @Override
    public Future<String> authenticated(String resourceId) {
        return perform(PolicyFixtures.OP_AUTHENTICATED, resourceId);
    }

    @Override
    public Future<String> operator(String resourceId) {
        return perform(PolicyFixtures.OP_OPERATOR, resourceId);
    }

    @Override
    public Future<String> scoped(String resourceId) {
        return perform(PolicyFixtures.OP_SCOPED, resourceId);
    }

    @Override
    public Future<String> action(String resourceId) {
        return perform(PolicyFixtures.OP_ACTION, resourceId);
    }

    @Override
    public Future<String> operatorAction(String resourceId) {
        return perform(PolicyFixtures.OP_OPERATOR_ACTION, resourceId);
    }

    @Override
    public Future<String> owned(String resourceId) {
        if (!eligibility.isEligible(resourceId)) {
            return Future.failedFuture(new NotEligibleException(resourceId));
        }
        return perform(PolicyFixtures.OP_OWNED, resourceId);
    }

    private Future<String> perform(String operation, String resourceId) {
        String actorId = ContextValues.current(SecurityContext.class)
                .map(context -> context.identity().actor().id())
                .orElse("none");
        effects.record(PATTERN, operation, actorId);
        return Future.succeededFuture(PATTERN + ":" + operation + ":" + resourceId);
    }
}
