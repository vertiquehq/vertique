// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import jakarta.annotation.Nullable;
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
        private Set<OperationInterceptor> operationInterceptors = Set.of();
        private List<RequestBodyDecoder> sortedDecoders = List.of(new JsonRequestBodyDecoder());
        private List<ResponseBodyEncoder> encoders = List.of(new StringBodyEncoder(), new JsonBodyEncoder());
        private JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();
        private dev.vertique.core.json.JsonMapperProfileRegistry jsonMapperProfileRegistry =
                new dev.vertique.json.DefaultJsonMapperProfileRegistry(Set.of());
        private ExceptionMapperRegistry exceptionMapperRegistry = null;
        private ParamConversionResolver paramConversionResolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        private SecurityPolicyValidator securityPolicyValidator = null;
        private Optional<AuthEnforcementCapability> authEnforcementCapability = Optional.empty();
        private Optional<ActionRegistry> actionRegistry = Optional.empty();
        private Optional<Authorizer> authorizer = Optional.empty();
        private Optional<BeanValidator> beanValidator = Optional.empty();

        /**
         * {@code null} (the default) keeps the retained public {@code Factory} constructor path, with
         * no sink set threaded through at all. A non-{@code null} value — including an empty set —
         * selects the package-private {@code @Inject} constructor and is passed as its 30th and last
         * parameter (T006).
         */
        private @Nullable Set<OperationPublicationSink> publicationSinks;

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
         * Sets the operation interceptors (defaults to none).
         *
         * @param interceptors the operation interceptors
         * @return this builder
         */
        Builder operationInterceptors(Set<OperationInterceptor> interceptors) {
            this.operationInterceptors = interceptors;
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
         * Sets the priority-sorted response body encoders used by both the response serializer and
         * the factory's {@code sortedEncoders} (defaults to a string encoder plus a JSON encoder).
         * Override to exercise a non-default encoder such as {@link ReadStreamBodyEncoder}.
         *
         * @param encoders the priority-sorted encoders
         * @return this builder
         */
        Builder encoders(List<ResponseBodyEncoder> encoders) {
            this.encoders = encoders;
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
         * (defaults to a registry carrying only the reserved built-ins, so every route resolves the
         * {@code vertique} floor). Override to register an application profile a resource selects via
         * {@code @JsonProfile}.
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
         * Sets the optional {@link SecurityPolicyValidator} (defaults to {@code null}, today's
         * value: no auth module).
         *
         * @param validator the security policy validator, or {@code null}
         * @return this builder
         */
        Builder securityPolicyValidator(SecurityPolicyValidator validator) {
            this.securityPolicyValidator = validator;
            return this;
        }

        /**
         * Sets the optional {@link AuthEnforcementCapability} marker (defaults to {@link
         * Optional#empty()}, today's value: the auth-enforcement runtime is not installed).
         *
         * @param capability the capability marker, present when the auth-enforcement runtime is
         *                   installed
         * @return this builder
         */
        Builder authEnforcementCapability(Optional<AuthEnforcementCapability> capability) {
            this.authEnforcementCapability = capability;
            return this;
        }

        /**
         * Sets the optional {@link ActionRegistry} (defaults to {@link Optional#empty()}: the
         * authorization engine is not installed, so any {@code @RequiresAction} operation fails
         * startup).
         *
         * @param registry the action registry, present when the authorization engine is installed
         * @return this builder
         */
        Builder actionRegistry(Optional<ActionRegistry> registry) {
            this.actionRegistry = registry;
            return this;
        }

        /**
         * Sets the optional core {@link Authorizer} (defaults to {@link Optional#empty()}: no
         * authorizer is installed, so any {@code @RequiresAction} operation fails startup).
         *
         * @param authorizer the authorizer, present when the authorization engine is installed
         * @return this builder
         */
        Builder authorizer(Optional<Authorizer> authorizer) {
            this.authorizer = authorizer;
            return this;
        }

        /**
         * Sets the optional {@link BeanValidator} (defaults to {@link Optional#empty()}: no Bean
         * Validation implementation is bound, so the invoker never checks parameters).
         *
         * @param validator the bean validator, present when a Bean Validation implementation is bound
         * @return this builder
         */
        Builder beanValidator(Optional<BeanValidator> validator) {
            this.beanValidator = validator;
            return this;
        }

        /**
         * Sets the {@code Set<OperationPublicationSink>} multibinding (T006). Leaving this unset
         * (the default, {@code null}) keeps the factory built through the retained public
         * constructor, exactly today's behavior; passing a set — including {@link Set#of()} — selects
         * the package-private {@code @Inject} constructor and threads it through as the sink set.
         *
         * @param sinks the sink set, or {@code null} to keep the public-constructor path
         * @return this builder
         */
        Builder publicationSinks(@Nullable Set<OperationPublicationSink> sinks) {
            this.publicationSinks = sinks;
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
            DefaultResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
            HttpConfig httpConfig = HttpConfig.builder().build();

            if (publicationSinks == null) {
                return new JaxRsRouterMount.Factory(
                        Set.of(), // routerLifecycleHooks
                        operationInterceptors,
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
                        securityPolicyValidator, // securityPolicyValidator (nullable)
                        authEnforcementCapability, // authEnforcementCapability
                        sortedDecoders, // sortedDecoders — needed for body binding
                        encoders, // sortedEncoders
                        httpConfig,
                        jaxRsConfig,
                        jsonMapperProfileRegistry, // jsonMapperProfileRegistry
                        dev.vertique.json.JsonConfig
                                .defaults(), // jsonConfig (json.jsonProfile unset => vertique floor)
                        beanValidator, // beanValidator
                        Optional.empty(), // objectProcessor
                        Set.of(), // evidenceCapturers
                        actionRegistry,
                        authorizer,
                        fileContentVerifiers,
                        validationStrategies,
                        operationSchemaSource);
            }

            // A non-null publicationSinks (including an empty set) selects the package-private
            // 30-parameter @Inject constructor T006 adds, with the sink set as its last parameter.
            return new JaxRsRouterMount.Factory(
                    Set.of(), // routerLifecycleHooks
                    operationInterceptors,
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
                    securityPolicyValidator, // securityPolicyValidator (nullable)
                    authEnforcementCapability, // authEnforcementCapability
                    sortedDecoders, // sortedDecoders — needed for body binding
                    encoders, // sortedEncoders
                    httpConfig,
                    jaxRsConfig,
                    jsonMapperProfileRegistry, // jsonMapperProfileRegistry
                    dev.vertique.json.JsonConfig.defaults(), // jsonConfig (json.jsonProfile unset => vertique floor)
                    beanValidator, // beanValidator
                    Optional.empty(), // objectProcessor
                    Set.of(), // evidenceCapturers
                    actionRegistry,
                    authorizer,
                    fileContentVerifiers,
                    validationStrategies,
                    operationSchemaSource,
                    publicationSinks);
        }
    }
}
