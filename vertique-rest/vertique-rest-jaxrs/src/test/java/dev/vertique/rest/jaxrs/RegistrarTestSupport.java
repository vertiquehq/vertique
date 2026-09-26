// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.mockito.Mockito.mock;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.MountMeta;
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

    /**
     * Shared {@link MountMeta} fixture for tests that exercise {@link JaxRsRouteRegistrar#registerAll}
     * directly (via this helper) and do not vary the mount metadata threaded to the mount-aware 3-arg
     * {@link RequestValidationStrategy#gateFor(dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor,
     * dev.vertique.rest.jaxrs.validation.OperationSchemas, MountMeta)} call.
     */
    static final MountMeta TEST_MOUNT_META = new MountMeta("test-mount", "/", null, Set.of());

    private RegistrarTestSupport() {}

    /**
     * Invokes {@link JaxRsRouteRegistrar#registerAll} with the {@code none} validation strategy, an
     * empty schema source, and an empty security collector, exposing the parameters the validation
     * unit tests vary.
     *
     * @param registrar             the registrar under test
     * @param resources             the resource instances to scan
     * @param apiRouter             the plain Vert.x router to register routes on
     * @param mount                 the mount metadata threaded to the registrar
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
            MountMeta mount,
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
                mount,
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
     * @param mount                 the mount metadata threaded to the registrar
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
            MountMeta mount,
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
                mount,
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

    /**
     * Variant for tests of the request-evidence capturer seam: registers {@code resources} with the
     * given capturers and the same inert defaults as the overloads above.
     *
     * @param registrar the registrar under test
     * @param resources the resource instances to scan
     * @param apiRouter the plain Vert.x router to register routes on
     * @param mount     the mount metadata threaded to the registrar
     * @param capturers the request-evidence capturers, already in invocation order
     */
    static void registerAllWithCapturers(
            JaxRsRouteRegistrar registrar,
            Set<Object> resources,
            Router apiRouter,
            MountMeta mount,
            List<dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer> capturers) {
        registrar.registerAll(
                resources,
                apiRouter,
                new NoneValidationStrategy(),
                mount,
                Optional.<OperationSchemaSource>empty(),
                new SecuritySchemeHandlerCollector(),
                List.of(),
                List.of(),
                mock(ErrorPipeline.class),
                mock(ResponsePipeline.class),
                new RestContextResolution(Set.of()),
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null,
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                capturers,
                null,
                false,
                JaxRsConfig.builder().build(),
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults());
    }

    /**
     * Variant for tests that vary the schema seam and the JSON profile configuration: it exposes the
     * three arguments the overloads above hard-code — the {@link OperationSchemaSource}, the
     * {@link JaxRsConfig} carrying {@code jaxrs.jsonProfile}, and the {@link JsonConfig} carrying
     * {@code json.jsonProfile} — and fills everything else with the same inert defaults they use: the
     * {@code none} validation strategy, an empty security collector, no interceptors, contributors or
     * evidence capturers, no security policy validator, auth disabled, no decoders or encoders,
     * {@code "OFF"} media-type validation, no bean validator, object processor or action registry, and
     * a profile registry holding only the built-in {@code system}, {@code vertique} and
     * {@code vertique-strict} profiles.
     *
     * @param registrar    the registrar under test
     * @param resources    the resource instances to scan
     * @param apiRouter    the plain Vert.x router to register routes on
     * @param mount        the mount metadata threaded to the registrar
     * @param schemaSource the schema source the registrar calls once per operation at router build
     * @param jaxRsConfig  the JAX-RS config supplying the {@code jaxrs.jsonProfile} tier
     * @param jsonConfig   the global JSON config supplying the {@code json.jsonProfile} tier and the
     *                     {@code vertique} floor
     */
    static void registerAll(
            JaxRsRouteRegistrar registrar,
            Set<Object> resources,
            Router apiRouter,
            MountMeta mount,
            Optional<OperationSchemaSource> schemaSource,
            JaxRsConfig jaxRsConfig,
            JsonConfig jsonConfig) {
        registrar.registerAll(
                resources,
                apiRouter,
                new NoneValidationStrategy(),
                mount,
                schemaSource,
                new SecuritySchemeHandlerCollector(),
                List.of(),
                List.of(),
                mock(ErrorPipeline.class),
                mock(ResponsePipeline.class),
                new RestContextResolution(Set.of()),
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null,
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                List.of(),
                null,
                false,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()),
                jsonConfig);
    }
}
