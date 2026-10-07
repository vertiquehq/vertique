// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.DecisionLog;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.DenialRecovery;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.MarkerIdentityResolver;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.RecordingAuthorizer;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Bindings of the typed access-policy proof application: the JWT provider, the HTTP verticle, the
 * recording authorization engine and decision log, the marked-caller identity resolver, and the
 * interceptor that tries to recover denials.
 */
@Module
public abstract class PolicyTestModule {

    /**
     * Provides the JWT authentication provider over the key the test token factory shares.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", PolicyFixtures.JWT_KEY);
    }

    /**
     * Registers the HTTP verticle for deployment in the edge phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }

    /**
     * Provides the action registry the recording authorizer validates against.
     *
     * @param authorizer the recording authorizer
     * @return its registry
     */
    @Provides
    @Singleton
    static ActionRegistry actionRegistry(RecordingAuthorizer authorizer) {
        return authorizer.registry();
    }

    /**
     * Provides the identity resolver that turns a marked JWT into a marked caller.
     *
     * @return the resolver, which runs before the framework default
     */
    @Provides
    @IntoSet
    static SecurityIdentityResolver markerIdentityResolver() {
        return new MarkerIdentityResolver();
    }

    /**
     * Binds the recording authorizer as the configured authorizer of the REST and service boundaries.
     *
     * @param authorizer the recording authorizer
     * @return the same instance as an {@link Authorizer}
     */
    @Binds
    abstract Authorizer authorizer(RecordingAuthorizer authorizer);

    /**
     * Contributes the decision log as an event observer.
     *
     * @param log the decision log
     * @return the same instance as an observer
     */
    @Binds
    @IntoSet
    abstract SecurityEventObserver decisionObserver(DecisionLog log);

    /**
     * Contributes the interceptor that attempts to recover every authorization denial.
     *
     * @param recovery the recovery interceptor
     * @return the same instance as a service interceptor
     */
    @Binds
    @IntoSet
    abstract ServiceInterceptor denialRecovery(DenialRecovery recovery);
}
