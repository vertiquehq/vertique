// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Test graph over {@link RestTestFixtureModule} <b>without</b> {@link RestTestNoSecurityModule}, whose
 * {@link SecurityPolicyValidator} is supplied from outside the fixture entirely.
 *
 * <p>This is the surrogate for the composition that matters in production use: a consumer that
 * includes {@code AuthModule} from {@code vertique-rest-security} and gets the framework's real
 * {@code DefaultSecurityPolicyValidator}. That module cannot be named here — {@code vertique-rest-test}
 * does not depend on {@code vertique-rest-security}, and adding the dependency just to name it in a
 * test would invert the layering. What this component pins is the property the split exists for: the
 * <em>unqualified</em> {@code SecurityPolicyValidator} key is free for another binding to claim.
 * {@code AuthModule} claims it with a {@code @Provides}; this claims it with a {@code @BindsInstance},
 * which is the same key from Dagger's point of view — so if {@link RestTestFixtureModule} ever binds
 * that key again, this file stops compiling with a duplicate-binding error, exactly as a consumer's
 * {@code AuthModule} component would.
 *
 * @see FixtureSelfTestComponent the unsecured counterpart, which includes
 *     {@link RestTestNoSecurityModule}
 */
@Singleton
@Component(modules = RestTestFixtureModule.class)
interface ExternalSecurityMountComponent {

    /**
     * Returns the mount handle built from a graph whose policy validator came from outside the
     * fixture.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /** Factory binding the fixture inputs plus the externally supplied policy validator. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the externally-secured graph.
         *
         * @param vertx                   the Vert.x instance
         * @param config                  the application configuration
         * @param contributions           the additive test contributions
         * @param securityPolicyValidator the validator standing in for the one a real security module
         *                                would provide
         * @return the assembled component
         */
        ExternalSecurityMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions,
                @BindsInstance SecurityPolicyValidator securityPolicyValidator);
    }
}
