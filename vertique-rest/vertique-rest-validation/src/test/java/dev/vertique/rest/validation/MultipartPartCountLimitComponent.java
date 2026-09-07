// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.test.RestTestNoSecurityModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Test graph over {@link RestTestFixtureModule} for {@link MultipartPartCountLimitIT}, mirroring
 * {@link ValidationMountComponent}: it additionally includes {@link RestValidationModule}, so a mount
 * built from it carries the real injected {@link WebValidationStrategy} and
 * {@link AnnotationSchemaSource} rather than a hand-rolled stand-in, and {@link
 * RestTestNoSecurityModule} for the {@code null} {@code SecurityPolicyValidator} stand-in these
 * validation-only tests need — see {@link ValidationMountComponent}'s javadoc for why.
 *
 * <p>{@link MultipartPartCountLimitIT.FailureCapture} is an {@link ErrorInterceptor}, for which
 * {@code RestTestFixtureModule} has no contribution seam: {@code RestCoreModule} declares
 * {@code Set<ErrorInterceptor>} as a bare {@code @Multibinds}, and nothing else populates it. Each
 * test binds its own per-test instance into the graph through {@link Factory#create}, and
 * {@link ErrorInterceptorModule} lifts that bound instance into the framework multibinding locally —
 * mirroring {@code JwtClaimsRejectionComponent.ContributorModule} in {@code vertique-rest-auth-jwt},
 * which does the same for {@code Set<OperationHandlerContributor>}. {@link
 * MultipartPartCountLimitIT.PartCountCapture} is a {@link dev.vertique.rest.core.interceptor.RequestInterceptor}, which
 * {@code RestTestFixtureModule} <em>does</em> have a seam for, so it travels through {@link
 * RestTestContributions.Builder#addRequestInterceptor} instead of a component-local module.
 *
 * <p>The {@link HttpConfig} form limits and the per-test uploads directory travel in as the
 * production {@code @VertxConfig JsonObject}, under the {@code "http"} section
 * {@code RestCoreModule.httpConfig} parses, and {@link #httpConfig()} exposes the graph's own parsed
 * instance so the test builds its server's {@code HttpServerOptions} from the exact same
 * {@link HttpConfig} the mount's body decoder was configured with, rather than a second one that
 * could drift from it.
 *
 * <p>Because the graph is the real {@code RestCoreModule} composition, its API-scoped
 * {@code ContentTypeValidationMiddleware} now runs on every request in this suite; it accepts
 * {@code multipart/form-data}, so it is inert for these tests (the old hand-rolled factory installed no
 * middleware at all).
 */
@Singleton
@Component(
        modules = {
            RestTestFixtureModule.class,
            RestTestNoSecurityModule.class,
            RestValidationModule.class,
            MultipartPartCountLimitComponent.ErrorInterceptorModule.class
        })
interface MultipartPartCountLimitComponent {

    /**
     * Returns the mount handle assembled by the framework graph, paired with the graph's complete
     * middleware set so {@link RestTestMounts} can install both the ROOT and the API middleware tier.
     *
     * <p>Named {@code testMount} rather than {@code factory}: a component that declares a
     * {@link Component.Factory} gets a generated static {@code factory()} on its {@code Dagger…}
     * class, and Dagger rejects a component method that collides with it.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /**
     * Returns the {@link HttpConfig} the graph parsed from the {@code @VertxConfig JsonObject} passed
     * to {@link Factory#create}, so the test starts its server with the identical effective
     * configuration the mount's body decoder was built from.
     *
     * @return the graph's parsed {@link HttpConfig}
     */
    HttpConfig httpConfig();

    /** Factory binding the instances a consumer supplies to the graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the part-count-limit mount graph.
         *
         * @param vertx         the Vert.x instance
         * @param config        the application configuration, exactly as {@code VertxModule} would
         *                      supply it in production, carrying the per-test {@code http.host} and
         *                      {@code http.uploadsDirectory}
         * @param contributions the additive test contributions (the part-count request interceptor)
         * @param failureCapture the {@link ErrorInterceptor} under test, recording the shape of any
         *                       failure the error pipeline received
         * @return the assembled component
         */
        MultipartPartCountLimitComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions,
                @BindsInstance MultipartPartCountLimitIT.FailureCapture failureCapture);
    }

    /**
     * Lifts the test's single bound {@link MultipartPartCountLimitIT.FailureCapture} into the
     * framework's {@code Set<ErrorInterceptor>} multibinding. {@link RestTestFixtureModule} has no
     * seam of its own for an error interceptor, so this component supplies one locally.
     */
    @Module
    abstract class ErrorInterceptorModule {

        /** Not instantiable. */
        private ErrorInterceptorModule() {}

        /**
         * Contributes the bound {@link MultipartPartCountLimitIT.FailureCapture} into
         * {@code Set<ErrorInterceptor>}.
         *
         * @param failureCapture the bound failure-capture instance
         * @return the same instance, as an {@link ErrorInterceptor}
         */
        @Provides
        @IntoSet
        static ErrorInterceptor bindFailureCapture(MultipartPartCountLimitIT.FailureCapture failureCapture) {
            return failureCapture;
        }
    }
}
