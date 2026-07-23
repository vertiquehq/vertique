// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.mockito.Mockito.mock;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.request.InputObjectProcessor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.ext.web.Router;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Test-only helper that adapts the rich {@link JaxRsRouteRegistrar#registerAll} signature to the
 * arguments the registrar/validator unit tests actually vary, filling the remainder with inert
 * defaults.
 *
 * <p>The migration to the plain {@link Router} added the {@link RequestValidationStrategy},
 * {@link OperationSchemaSource}, and {@link SecuritySchemeHandlerCollector} parameters and dropped the
 * OpenAPI {@code RouterBuilder} and {@code OpenAPIContract}. These tests exercise startup validation
 * (duplicate operationIds, multiple body params, {@code @RequiresAction} gating, media-type checks,
 * contributor invocation) and never depend on validation gating, so this helper uses the {@code none}
 * strategy with no schema source — keeping the call sites focused on the variables under test.
 */
final class RegistrarTestSupport {

    private RegistrarTestSupport() {}

    /**
     * Invokes {@link JaxRsRouteRegistrar#registerAll} with the {@code none} validation strategy, an
     * empty schema source, and an empty security collector, exposing the parameters the validation
     * unit tests vary.
     *
     * @param registrar             the registrar under test
     * @param resources             the resource instances to scan
     * @param apiRouter             the plain Vert.x router to register routes on
     * @param operationInterceptors the operation interceptors
     * @param contributors          the operation handler contributors
     * @param securityPolicyValidator optional security policy validator; {@code null} when absent
     * @param authEnabled           whether auth enforcement is installed
     * @param decoders              request body decoders
     * @param encoders              response body encoders
     * @param mediaTypeValidation   the media-type validation mode ({@code "WARN"}/{@code "STRICT"}/{@code "OFF"})
     * @param beanValidator         optional bean validator; {@code null} when absent
     * @param objectProcessor       optional input object processor; {@code null} when absent
     * @param actionRegistry        optional action registry; {@code null} when the authz engine is absent
     * @param authorizerAvailable   whether the core {@code Authorizer} is installed
     */
    static void registerAll(
            JaxRsRouteRegistrar registrar,
            Set<Object> resources,
            Router apiRouter,
            List<OperationInterceptor> operationInterceptors,
            List<OperationHandlerContributor> contributors,
            @Nullable SecurityPolicyValidator securityPolicyValidator,
            boolean authEnabled,
            List<RequestBodyDecoder> decoders,
            List<ResponseBodyEncoder> encoders,
            String mediaTypeValidation,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            @Nullable dev.vertique.security.authz.ActionRegistry actionRegistry,
            boolean authorizerAvailable) {
        registerAll(
                registrar,
                resources,
                apiRouter,
                new SecuritySchemeHandlerCollector(),
                operationInterceptors,
                contributors,
                securityPolicyValidator,
                authEnabled,
                decoders,
                encoders,
                mediaTypeValidation,
                beanValidator,
                objectProcessor,
                actionRegistry,
                authorizerAvailable);
    }

    /**
     * Variant exposing the {@link SecuritySchemeHandlerCollector} so tests of the registrar's
     * fail-closed security gate can supply a collector with (or without) a handler for a declared
     * scheme.
     *
     * @param registrar             the registrar under test
     * @param resources             the resource instances to scan
     * @param apiRouter             the plain Vert.x router to register routes on
     * @param securityHandlers      the collected authentication handlers keyed by scheme name
     * @param operationInterceptors the operation interceptors
     * @param contributors          the operation handler contributors
     * @param securityPolicyValidator optional security policy validator; {@code null} when absent
     * @param authEnabled           whether auth enforcement is installed
     * @param decoders              request body decoders
     * @param encoders              response body encoders
     * @param mediaTypeValidation   the media-type validation mode ({@code "WARN"}/{@code "STRICT"}/{@code "OFF"})
     * @param beanValidator         optional bean validator; {@code null} when absent
     * @param objectProcessor       optional input object processor; {@code null} when absent
     * @param actionRegistry        optional action registry; {@code null} when the authz engine is absent
     * @param authorizerAvailable   whether the core {@code Authorizer} is installed
     */
    static void registerAll(
            JaxRsRouteRegistrar registrar,
            Set<Object> resources,
            Router apiRouter,
            SecuritySchemeHandlerCollector securityHandlers,
            List<OperationInterceptor> operationInterceptors,
            List<OperationHandlerContributor> contributors,
            @Nullable SecurityPolicyValidator securityPolicyValidator,
            boolean authEnabled,
            List<RequestBodyDecoder> decoders,
            List<ResponseBodyEncoder> encoders,
            String mediaTypeValidation,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            @Nullable dev.vertique.security.authz.ActionRegistry actionRegistry,
            boolean authorizerAvailable) {
        registrar.registerAll(
                resources,
                apiRouter,
                new NoneValidationStrategy(),
                Optional.<OperationSchemaSource>empty(),
                securityHandlers,
                operationInterceptors,
                contributors,
                mock(ErrorPipeline.class),
                mock(ResponsePipeline.class),
                new RestContextResolution(Set.of()),
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                securityPolicyValidator,
                authEnabled,
                decoders,
                encoders,
                mediaTypeValidation,
                beanValidator,
                objectProcessor,
                List.of(),
                actionRegistry,
                authorizerAvailable,
                JaxRsConfig.builder().build(),
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults());
    }
}
