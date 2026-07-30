// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.core.context.RestContextResolver;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Set;

/**
 * Dagger module that gives a test graph the framework's <em>real</em> REST collaborators.
 *
 * <p>It includes {@link RestModule} (which transitively includes {@code RestCoreModule} and the JSON
 * runtime) and {@link ConfigParsingModule}, so a component that includes this module resolves the
 * production {@code JaxRsRouterMount.Factory} — with the real response body encoders, request body
 * decoders, exception mapper registry, response serializer, and context resolvers. Several of those
 * are package-private in {@code vertique-rest-jaxrs} and {@code vertique-rest-core}; Dagger's
 * generated factories live in those packages, which is what makes them reachable here without
 * widening any production visibility.
 *
 * <p>Because Dagger builds the graph, the fixture never calls the 29-argument
 * {@code JaxRsRouterMount.Factory} constructor, and it <b>never constructs or reorders the
 * encoder/decoder lists</b>. Encoder and decoder order is produced by
 * {@code RestModule.sortedResponseBodyEncoders} and {@code RestModule.sortedRequestBodyDecoders},
 * and the response serializer is built from the same sorted list the factory receives — so ordering
 * and serializer coherence hold by construction.
 *
 * <p>The fixture defines no ordering policy of its own anywhere. Each middleware tier is installed
 * with the production {@link dev.vertique.core.extension.OrderedExtension} comparator at its
 * production-equivalent site: {@code JaxRsRouterMount} sorts and installs the API tier, and
 * {@link RestTestMounts} reproduces {@code HttpVerticle}'s ROOT-tier sort line for line on the root
 * router it builds. Middlewares are bound as an unordered {@code Set} with no {@code sorted…}
 * provider to delegate to, which is why that one sort is performed at the installation site rather
 * than inherited from a module.
 *
 * <h2>Usage</h2>
 *
 * <p>Declare one package-private test {@code @Component} per consuming Maven module, including any
 * strategy module the consumer needs alongside this one:
 *
 * <pre>{@code
 * @Singleton
 * @Component(modules = {RestTestFixtureModule.class, RestValidationModule.class})
 * interface ValidationMountComponent {
 *     RestTestMount testMount();
 *
 *     @Component.Factory
 *     interface Factory {
 *         ValidationMountComponent create(
 *                 @BindsInstance Vertx vertx,
 *                 @BindsInstance @VertxConfig JsonObject config,
 *                 @BindsInstance RestTestContributions contributions);
 *     }
 * }
 * }</pre>
 *
 * <p>Expose {@link RestTestMount}, not {@code JaxRsRouterMount.Factory}: the helpers in
 * {@link RestTestMounts} take the handle, and offer no factory-only overload, so the ROOT-scoped
 * middleware tier is not lost by accident on the way to the server (see {@link RestTestMount} for
 * what that does and does not guarantee). Avoid naming any component method
 * {@code factory()} — a component declaring a {@code @Component.Factory} gets a generated static
 * {@code factory()} on its {@code Dagger…} class, and Dagger rejects a collision.
 *
 * <p>Including the strategy's own module — rather than routing a strategy through this fixture — is
 * the only install path for request-validation strategies, so the graph never carries two strategies
 * with the same id.
 *
 * <h2>Configuration</h2>
 *
 * <p>Configuration flows in as the production {@code @VertxConfig JsonObject}, so every config-derived
 * collaborator ({@code HttpConfig}, {@code JaxRsConfig}, the SSE encoder, the default-header
 * middleware) is built from one coherent source. Note that {@code jaxrs.validationStrategy} defaults
 * to {@code "web-validation"} while this module alone contributes only the {@code none} strategy: a
 * component that includes no validation module <b>must</b> set
 * {@code {"jaxrs":{"validationStrategy":"none"}}}, or the router build fails when it resolves the
 * configured strategy id.
 *
 * <h2>Compatibility surface</h2>
 *
 * <p>The set of seams below is a <b>compatibility surface</b>. Removing a seam breaks every consumer
 * that contributes through it — and adding one is <em>not</em> free either: each seam reads a
 * component of {@link RestTestContributions}, which is a {@code record}, so a new seam requires a new
 * record component. That changes the canonical constructor's arity, which is <b>source-breaking</b>
 * for any caller that invokes the canonical constructor directly or destructures the record in a
 * record pattern, and <b>binary-breaking</b> ({@link NoSuchMethodError}) for consumers already
 * compiled against the previous arity.
 *
 * <p>Consumers should therefore build contributions through {@link RestTestContributions#builder()},
 * which is insulated from that change: a new seam adds an {@code addX} method to the builder and
 * leaves every existing call site compiling and linking unchanged. Treat the seam list as published
 * API even though the artifact itself is test support.
 *
 * @see RestTestContributions
 */
@Module(includes = {RestModule.class, ConfigParsingModule.class})
public abstract class RestTestFixtureModule {

    // --- Security stand-in ---

    /**
     * Provides a {@code null} {@link SecurityPolicyValidator}.
     *
     * <p>Neither {@code RestModule} nor {@code RestCoreModule} binds this type, yet
     * {@code JaxRsRouterMount.Factory} consumes it as {@code @Nullable}, so every graph must supply
     * it. This matches the stand-in an unauthenticated application declares in its own
     * {@code AppModule}; startup policy validation is skipped when it is absent. A consumer that
     * needs real policy validation includes the security module and does not use this fixture's
     * graph for that concern.
     *
     * @return always {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    // --- Contribution seams ---

    /**
     * Unions the contributed middlewares into the framework {@code Set<Middleware>} multibinding.
     *
     * @param contributions the test contributions
     * @return the contributed middlewares
     */
    @Provides
    @ElementsIntoSet
    static Set<Middleware> fixtureMiddlewares(RestTestContributions contributions) {
        return contributions.middlewares();
    }

    /**
     * Unions the contributed interceptors into the framework {@code Set<RequestInterceptor>}
     * multibinding.
     *
     * @param contributions the test contributions
     * @return the contributed request interceptors
     */
    @Provides
    @ElementsIntoSet
    static Set<RequestInterceptor> fixtureRequestInterceptors(RestTestContributions contributions) {
        return contributions.requestInterceptors();
    }

    /**
     * Unions the contributed encoders into the framework {@code Set<ResponseBodyEncoder>}
     * multibinding. {@code RestModule} then sorts the union — this seam contributes, it does not
     * order.
     *
     * @param contributions the test contributions
     * @return the contributed response body encoders
     */
    @Provides
    @ElementsIntoSet
    static Set<ResponseBodyEncoder> fixtureResponseBodyEncoders(RestTestContributions contributions) {
        return contributions.responseBodyEncoders();
    }

    /**
     * Unions the contributed decoders into the framework {@code Set<RequestBodyDecoder>}
     * multibinding. {@code RestModule} then sorts the union.
     *
     * @param contributions the test contributions
     * @return the contributed request body decoders
     */
    @Provides
    @ElementsIntoSet
    static Set<RequestBodyDecoder> fixtureRequestBodyDecoders(RestTestContributions contributions) {
        return contributions.requestBodyDecoders();
    }

    /**
     * Unions the contributed mappers into the framework {@code Set<ExceptionMapper<?>>} multibinding,
     * from which {@code RestModule} assembles the registry alongside its own
     * {@code DefaultExceptionMapper}.
     *
     * @param contributions the test contributions
     * @return the contributed exception mappers
     */
    @Provides
    @ElementsIntoSet
    static Set<ExceptionMapper<?>> fixtureExceptionMappers(RestTestContributions contributions) {
        return contributions.exceptionMappers();
    }

    /**
     * Unions the contributed profiles into the framework {@code Set<JsonMapperProfile>} multibinding.
     *
     * @param contributions the test contributions
     * @return the contributed JSON mapper profiles
     */
    @Provides
    @ElementsIntoSet
    static Set<JsonMapperProfile> fixtureJsonMapperProfiles(RestTestContributions contributions) {
        return contributions.jsonMapperProfiles();
    }

    /**
     * Unions the contributed verifiers into the framework {@code Set<FileContentVerifier>}
     * multibinding.
     *
     * @param contributions the test contributions
     * @return the contributed file-content verifiers
     */
    @Provides
    @ElementsIntoSet
    static Set<FileContentVerifier> fixtureFileContentVerifiers(RestTestContributions contributions) {
        return contributions.fileContentVerifiers();
    }

    /**
     * Unions the contributed resolvers into the framework {@code Set<RestContextResolver>}
     * multibinding, joining the three built-in resolvers behind {@code RestContextResolution}.
     *
     * @param contributions the test contributions
     * @return the contributed context resolvers
     */
    @Provides
    @ElementsIntoSet
    static Set<RestContextResolver> fixtureContextResolvers(RestTestContributions contributions) {
        return contributions.contextResolvers();
    }
}
