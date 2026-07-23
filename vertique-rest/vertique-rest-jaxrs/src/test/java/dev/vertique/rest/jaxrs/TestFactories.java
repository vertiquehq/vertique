// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Test-only builder for {@link JaxRsRouterMount.Factory} instances, defaulting all shared services to
 * inert stubs (a JSON encoder + decoder, the {@code none} validation strategy, no auth) so a test
 * needs only to override the few collaborators under test.
 *
 * <p>This centralizes the wide factory constructor argument list behind a small fluent surface, so
 * the routing ITs stay focused on the behavior they exercise rather than on factory plumbing.
 */
final class TestFactories {

    private TestFactories() {}

    /**
     * Returns a new builder pre-populated with inert defaults.
     *
     * @return a fresh builder
     */
    static Builder builder() {
        return new Builder();
    }

    /** Fluent builder accumulating the few collaborators a routing test overrides. */
    static final class Builder {
        private Set<RequestValidationStrategy> validationStrategies = Set.of(new NoneValidationStrategy());
        private Set<FileContentVerifier> fileContentVerifiers = Set.of();
        private Optional<OperationSchemaSource> operationSchemaSource = Optional.empty();
        private Set<SecuritySchemeHandler> securitySchemeHandlers = Set.of();
        private Set<OperationHandlerContributor> operationHandlerContributors = Set.of();
        private Set<Middleware> middlewares = Set.of();
        private Set<RequestInterceptor> requestInterceptors = Set.of();
        private List<RequestBodyDecoder> sortedDecoders = List.of(new JsonRequestBodyDecoder());
        private JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();
        private dev.vertique.core.json.JsonMapperProfileRegistry jsonMapperProfileRegistry =
                new dev.vertique.json.DefaultJsonMapperProfileRegistry(Set.of());
        private ExceptionMapperRegistry exceptionMapperRegistry = null;
        private ParamConversionResolver paramConversionResolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());

        /**
         * Sets the registered validation strategies.
         *
         * @param strategies the strategies to register
         * @return this builder
         */
        Builder validationStrategies(Set<RequestValidationStrategy> strategies) {
            this.validationStrategies = strategies;
            return this;
        }

        /**
         * Sets the file-content verifiers bound in the application graph.
         *
         * @param verifiers the file-content verifiers
         * @return this builder
         */
        Builder fileContentVerifiers(Set<FileContentVerifier> verifiers) {
            this.fileContentVerifiers = verifiers;
            return this;
        }

        /**
         * Sets the optional operation schema source.
         *
         * @param source the schema source
         * @return this builder
         */
        Builder operationSchemaSource(Optional<OperationSchemaSource> source) {
            this.operationSchemaSource = source;
            return this;
        }

        /**
         * Sets the security scheme handlers.
         *
         * @param handlers the scheme handlers
         * @return this builder
         */
        Builder securitySchemeHandlers(Set<SecuritySchemeHandler> handlers) {
            this.securitySchemeHandlers = handlers;
            return this;
        }

        /**
         * Sets the operation handler contributors.
         *
         * @param contributors the contributors
         * @return this builder
         */
        Builder operationHandlerContributors(Set<OperationHandlerContributor> contributors) {
            this.operationHandlerContributors = contributors;
            return this;
        }

        /**
         * Sets the API/root middlewares.
         *
         * @param middlewares the middlewares
         * @return this builder
         */
        Builder middlewares(Set<Middleware> middlewares) {
            this.middlewares = middlewares;
            return this;
        }

        /**
         * Sets the request interceptors.
         *
         * @param interceptors the request interceptors
         * @return this builder
         */
        Builder requestInterceptors(Set<RequestInterceptor> interceptors) {
            this.requestInterceptors = interceptors;
            return this;
        }

        /**
         * Sets the priority-sorted request body decoders used for body binding (defaults to a single
         * JSON decoder). Override to exercise the text/binary body paths.
         *
         * @param decoders the priority-sorted decoders
         * @return this builder
         */
        Builder sortedDecoders(List<RequestBodyDecoder> decoders) {
            this.sortedDecoders = decoders;
            return this;
        }

        /**
         * Sets the JAX-RS config (e.g. to select a validation strategy by id).
         *
         * @param config the JAX-RS config
         * @return this builder
         */
        Builder jaxRsConfig(JaxRsConfig config) {
            this.jaxRsConfig = config;
            return this;
        }

        /**
         * Sets the JSON mapper profile registry used to resolve per-method request-body profiles
         * (defaults to a vertx-only registry). Override to register a non-{@code vertx} profile a
         * resource selects via {@code @JsonProfile}.
         *
         * @param registry the profile registry
         * @return this builder
         */
        Builder jsonMapperProfileRegistry(dev.vertique.core.json.JsonMapperProfileRegistry registry) {
            this.jsonMapperProfileRegistry = registry;
            return this;
        }

        /**
         * Sets the {@link ExceptionMapperRegistry} the mount uses (defaults to a registry carrying only
         * the framework {@link DefaultExceptionMapper}). Override to exercise an app-contributed
         * {@code ExceptionMapper<T>} override.
         *
         * @param registry the exception-mapper registry
         * @return this builder
         */
        Builder exceptionMapperRegistry(ExceptionMapperRegistry registry) {
            this.exceptionMapperRegistry = registry;
            return this;
        }

        /**
         * Sets the {@link ParamConversionResolver} threaded into the binding path and used for startup
         * validation (defaults to a framework built-ins-only resolver). Override to register app
         * converter bindings or JAX-RS providers.
         *
         * @param resolver the conversion resolver
         * @return this builder
         */
        Builder paramConversionResolver(ParamConversionResolver resolver) {
            this.paramConversionResolver = resolver;
            return this;
        }

        /**
         * Builds the factory with the accumulated collaborators and inert defaults for the rest.
         *
         * @return a fully constructed factory
         */
        JaxRsRouterMount.Factory build() {
            DefaultExceptionMapper defaultMapper = RestModule.defaultExceptionMapper();
            ExceptionMapperRegistry registry = exceptionMapperRegistry != null
                    ? exceptionMapperRegistry
                    : new ExceptionMapperRegistry(defaultMapper, Set.of());
            RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
            RestContextResolution restContextResolution = new RestContextResolution(Set.of());
            List<dev.vertique.rest.core.response.ResponseBodyEncoder> encoders =
                    List.of(new StringBodyEncoder(), new JsonBodyEncoder());
            DefaultResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
            HttpConfig httpConfig = HttpConfig.builder().build();

            return new JaxRsRouterMount.Factory(
                    Set.of(), // routerLifecycleHooks
                    Set.of(), // operationInterceptors
                    Set.of(), // errorInterceptors
                    middlewares,
                    operationHandlerContributors,
                    securitySchemeHandlers,
                    requestInterceptors,
                    restExceptionMapper,
                    registry,
                    Set.of(), // responseProducerBindings
                    responseSerializer,
                    restContextResolution,
                    paramConversionResolver,
                    null, // securityPolicyValidator (nullable)
                    Optional.empty(), // authEnforcementCapability
                    sortedDecoders, // sortedDecoders — needed for body binding
                    encoders, // sortedEncoders
                    httpConfig,
                    jaxRsConfig,
                    jsonMapperProfileRegistry, // jsonMapperProfileRegistry
                    dev.vertique.json.JsonConfig.defaults(), // jsonConfig (global json.jsonProfile default)
                    Optional.empty(), // beanValidator
                    Optional.empty(), // objectProcessor
                    Set.of(), // evidenceCapturers
                    Optional.empty(), // actionRegistry
                    Optional.empty(), // authorizer
                    fileContentVerifiers,
                    validationStrategies,
                    operationSchemaSource);
        }
    }
}
