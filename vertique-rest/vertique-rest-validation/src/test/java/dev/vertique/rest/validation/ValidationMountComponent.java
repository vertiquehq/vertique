// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.runtime.MagicBytesVerifierModule;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Test graph over {@link RestTestFixtureModule} that additionally includes {@link RestValidationModule},
 * so a mount built from it carries the real injected {@link WebValidationStrategy} and
 * {@link AnnotationSchemaSource} rather than a hand-rolled stand-in.
 *
 * <p>Declared per {@code vertique-rest-test}'s consumer-component idiom (see the
 * {@link RestTestFixtureModule} javadoc): one package-private test {@code @Component} per consuming
 * Maven module, including any strategy module the module needs alongside the fixture module. Consumed
 * through {@link MountFixtures} rather than directly by individual integration tests.
 */
@Singleton
@Component(modules = {RestTestFixtureModule.class, RestValidationModule.class})
interface ValidationMountComponent {

    /**
     * Returns the mount handle assembled by the framework graph — the real
     * {@link JaxRsRouterMount.Factory} wired with the {@code web-validation} request-validation
     * strategy, paired with the graph's complete middleware set so {@link RestTestMounts} can install
     * both the ROOT and the API middleware tier.
     *
     * <p>Named {@code testMount} rather than {@code factory}: a component that declares a
     * {@link Component.Factory} gets a generated static {@code factory()} on its {@code Dagger…} class,
     * and Dagger rejects a component method that collides with it.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /** Factory binding the three instances a consumer supplies to the graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the validation mount graph.
         *
         * @param vertx         the Vert.x instance
         * @param config        the application configuration, exactly as {@code VertxModule} would
         *                      supply it in production
         * @param contributions the additive test contributions
         * @return the assembled component
         */
        ValidationMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }

    /**
     * Test graph reaching the opt-in magic-bytes {@link FileContentVerifier} through {@link
     * MagicBytesVerifierModule} without naming its package-private implementation.
     *
     * <p>Deliberately <b>not</b> one of {@link ValidationMountComponent}'s own modules:
     * {@code MagicBytesVerifierModule} is framework opt-in (its javadoc: "intentionally not included
     * by the framework's default component"). Folding it into the always-active
     * {@code ValidationMountComponent} graph would make the verifier a standing member of every
     * consumer's {@code Set<FileContentVerifier>} — breaking tests such as {@link
     * FileVerifierRejectionIT} that assert on a verifier set they control explicitly through {@link
     * RestTestContributions}. A test that wants the real verifier resolves it here, once, then
     * contributes the instance via {@link RestTestContributions.Builder#addFileContentVerifier}
     * before building its mount through {@link ValidationMountComponent} — the same additive path
     * every other opt-in extension takes. Co-located here, rather than nested in the one integration
     * test that needs it, so the module's test tree carries a single home for Dagger test-fixture
     * components.
     */
    @Singleton
    @Component(modules = MagicBytesVerifierModule.class)
    interface MagicBytesVerifierComponent {

        /**
         * Returns the {@code Set<FileContentVerifier>} contributed by {@link MagicBytesVerifierModule}
         * — the real, package-private magic-bytes verifier reached through its public module.
         *
         * @return the magic-bytes verifier set
         */
        Set<FileContentVerifier> fileContentVerifiers();

        /** Factory binding the one instance the magic-bytes graph needs. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the magic-bytes verifier graph.
             *
             * @param vertx the Vert.x instance, used for the verifier's asynchronous file reads
             * @return the assembled component
             */
            MagicBytesVerifierComponent create(@BindsInstance Vertx vertx);
        }
    }
}
