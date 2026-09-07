// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.test.RestTestNoSecurityModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Test graph over {@link RestTestFixtureModule} for {@link JwtClaimsRejectionStatusIT}, declared
 * per {@code vertique-rest-test}'s consumer-component idiom (see the {@link RestTestFixtureModule}
 * javadoc): one package-private test {@code @Component} per consuming Maven module. Mirrors
 * {@code ValidationMountComponent} in {@code vertique-rest-validation}.
 *
 * <p>{@link RestTestNoSecurityModule} is included because {@link JwtClaimsRejectionStatusIT}
 * exercises claims validation, not startup security policy: it supplies the {@code null}
 * {@code SecurityPolicyValidator} the mount factory requires, so startup policy validation is
 * skipped. A graph that wanted the framework's real policy checks would include {@code AuthModule}
 * in its place — the two bind the same unqualified Dagger key and cannot both be present.
 *
 * <p>The rejecting {@link JwtClaimsValidatorContributor} under test carries
 * {@link JwtClaimsRejectionStatusIT}'s own validator lambda and recording rejection reporter, so
 * {@link JwtClaimsRejectionStatusIT} constructs it and binds the single instance into the graph
 * through {@link Factory#create}. {@link ContributorModule} then lifts that bound instance into the
 * framework's {@code Set<OperationHandlerContributor>} multibinding — the seam
 * {@code RestCoreModule} declares as a bare {@code @Multibinds} and that
 * {@link RestTestFixtureModule} does not otherwise populate.
 */
@Singleton
@Component(
        modules = {
            RestTestFixtureModule.class,
            RestTestNoSecurityModule.class,
            JwtClaimsRejectionComponent.ContributorModule.class
        })
interface JwtClaimsRejectionComponent {

    /**
     * Returns the mount handle assembled by the framework graph, paired with the graph's complete
     * middleware set so {@link RestTestMounts} can install both the ROOT and the API middleware
     * tier.
     *
     * <p>Named {@code testMount} rather than {@code factory}: a component that declares a
     * {@link Component.Factory} gets a generated static {@code factory()} on its {@code Dagger…}
     * class, and Dagger rejects a component method that collides with it.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /** Factory binding the instances a consumer supplies to the graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the claims-rejection mount graph.
         *
         * @param vertx                      the Vert.x instance
         * @param config                     the application configuration, exactly as
         *                                   {@code VertxModule} would supply it in production; must
         *                                   set {@code jaxrs.validationStrategy} to {@code "none"}
         *                                   since this graph includes no validation module
         * @param contributions              the additive test contributions (the stub bearer
         *                                   middleware)
         * @param claimsRejectionContributor the rejecting {@link JwtClaimsValidatorContributor}
         *                                   under test
         * @return the assembled component
         */
        JwtClaimsRejectionComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions,
                @BindsInstance JwtClaimsValidatorContributor claimsRejectionContributor);
    }

    /**
     * Lifts the test's single bound {@link JwtClaimsValidatorContributor} into the framework's
     * {@code Set<OperationHandlerContributor>} multibinding. {@link RestTestFixtureModule} has no
     * seam of its own for an operation-handler contributor, so this component supplies one locally.
     */
    @Module
    abstract class ContributorModule {

        /** Not instantiable. */
        private ContributorModule() {}

        /**
         * Contributes the bound {@link JwtClaimsValidatorContributor} into
         * {@code Set<OperationHandlerContributor>}.
         *
         * @param contributor the bound contributor instance
         * @return the same instance, as an {@link OperationHandlerContributor}
         */
        @Provides
        @IntoSet
        static OperationHandlerContributor claimsRejectionContributor(JwtClaimsValidatorContributor contributor) {
            return contributor;
        }
    }
}
