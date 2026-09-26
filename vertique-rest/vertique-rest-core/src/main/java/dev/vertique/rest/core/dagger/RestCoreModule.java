// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.rest.core.capture.RestRequestCaptureCoordinator;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import dev.vertique.rest.core.config.CorsConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.config.JaxRsSecurityConfig;
import dev.vertique.rest.core.context.RestContextModule;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.core.correlation.CorrelationIngressModule;
import dev.vertique.rest.core.events.OperationIdCaptureContributor;
import dev.vertique.rest.core.events.RequestCompletionScope;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware;
import dev.vertique.rest.core.middleware.ContextualLoggingMiddleware;
import dev.vertique.rest.core.middleware.DefaultHeadersMiddleware;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.RouterCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.CorsHandler;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dagger module providing core REST framework bindings: multibinding declarations,
 * default configuration, and standard middleware.
 *
 * <p>Extension libraries depend on this module for multibinding sets. Application
 * modules typically include {@code RestModule} from {@code rest-jaxrs} instead,
 * which auto-includes this module and adds the JAX-RS routing runtime.
 */
@Module(
        includes = {
            ContextRuntimeModule.class,
            LoggingContextModule.class,
            CorrelationContextModule.class,
            CorrelationIngressModule.class,
            RestContextModule.class
        })
public abstract class RestCoreModule {

    private static final Logger log = LoggerFactory.getLogger(RestCoreModule.class);

    // --- Multibinding declarations ---

    @Multibinds
    abstract Set<RouterCustomizer> routerCustomizers();

    @Multibinds
    abstract Set<RouterLifecycleHook> routerLifecycleHooks();

    @Multibinds
    abstract Set<OperationInterceptor> operationInterceptors();

    @Multibinds
    abstract Set<ErrorInterceptor> errorInterceptors();

    @Multibinds
    abstract Set<RequestInterceptor> requestInterceptors();

    @Multibinds
    abstract Set<Middleware> middlewares();

    @Multibinds
    abstract Set<ExceptionMapper<?>> exceptionMappers();

    @Multibinds
    abstract Set<OperationHandlerContributor> operationHandlerContributors();

    @Multibinds
    abstract Set<RouterMount> routerMounts();

    @Multibinds
    abstract Set<MountCustomizer> mountCustomizers();

    @Multibinds
    abstract Set<MountCompositionValidator> mountCompositionValidators();

    @Multibinds
    abstract Set<SecuritySchemeHandler> securitySchemeHandlers();

    @Multibinds
    abstract Set<ResponseProducerBinding<?>> responseProducerBindings();

    @Multibinds
    abstract Set<ResponseBodyEncoder> responseBodyEncoders();

    @Multibinds
    abstract Set<RequestBodyDecoder> requestBodyDecoders();

    @Multibinds
    @JaxRsResources
    abstract Set<Object> jaxRsResources();

    @Multibinds
    abstract Set<RestRequestCompletedListener> restRequestCompletedListeners();

    @Multibinds
    abstract Set<RestServerRequestEvidenceCapturer> restServerRequestEvidenceCapturers();

    @Multibinds
    abstract Set<RestRequestCaptureCoordinator> restRequestCaptureCoordinators();

    /**
     * Declares the empty {@link ParamConverterProvider} multibinding set.
     * Applications contribute custom converters via {@code @Provides @IntoSet ParamConverterProvider}.
     *
     * @return an empty set (populated by Dagger from {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ParamConverterProvider> paramConverterProviders();

    /**
     * Declares the empty {@link ParamConverterBinding} multibinding set. Applications contribute
     * native, type-keyed converters via {@code @Provides @IntoSet ParamConverterBinding<?>}; these
     * override the framework built-ins for the same target type and participate in both inbound and
     * outbound conversion.
     *
     * @return an empty set (populated by Dagger from {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ParamConverterBinding<?>> paramConverterBindings();

    /**
     * Provides the native, type-keyed {@link ParamConverterRegistry}, combining the framework built-in
     * converters with the application's {@link ParamConverterBinding} contributions (application
     * bindings override built-ins for the same target type; two bindings for the same type fail at
     * construction).
     *
     * @param bindings the application-contributed converter bindings (empty when none)
     * @return the native converter registry
     */
    @Provides
    @Singleton
    static ParamConverterRegistry paramConverterRegistry(Set<ParamConverterBinding<?>> bindings) {
        return ParamConverterRegistry.of(bindings);
    }

    /**
     * Provides the full {@link ParamConversionResolver} (native registry → JAX-RS provider bridge →
     * error policy) shared by the JAX-RS inbound binding paths, startup validation, and the REST
     * client outbound serialization path. The JAX-RS provider set is the {@code @Multibinds
     * Set<ParamConverterProvider>} declared above; when empty, the lazy annotation supplier on each
     * {@code ConversionContext} is never materialized.
     *
     * @param registry  the native converter registry
     * @param providers the JAX-RS {@link ParamConverterProvider} set (empty when none)
     * @return the framework conversion resolver
     */
    @Provides
    @Singleton
    static ParamConversionResolver paramConversionResolver(
            ParamConverterRegistry registry, Set<ParamConverterProvider> providers) {
        return ParamConversionResolver.of(registry, providers);
    }

    @dagger.BindsOptionalOf
    abstract SecurityRuntime optionalSecurityRuntime();

    @dagger.BindsOptionalOf
    abstract AuthEnforcementCapability authEnforcementCapability();

    /**
     * Declares the {@link RequestCompletionScope} multibinding set.
     *
     * <p>Integration modules (e.g. {@code OpenTelemetryRestModule}) contribute implementations
     * via {@code @Provides @IntoSet}. When no integration is installed the set is empty and the
     * {@link RestRequestCompletionEmitter} behaves identically to the pre-SPI baseline
     * (no bracket overhead). Multiple integrations may contribute simultaneously — each scope
     * is opened in iteration order before listener dispatch and closed in reverse order after.
     *
     * @return the multibinding declaration (empty set when no contributors are present)
     */
    @Multibinds
    abstract Set<RequestCompletionScope> requestCompletionScopes();

    // --- Default bindings ---

    /**
     * The {@link JaxRsSecurityConfig} record component names, derived by reflection so a future
     * component cannot drift out of sync with the raw-key check below.
     */
    private static final Set<String> SECURITY_COMPONENT_NAMES = Arrays.stream(
                    JaxRsSecurityConfig.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * INFO announcement for the {@code jaxrs.security.requireExplicitPolicy} opt-in, logged
     * exactly once, after parsing, when the opt-in resolves to {@code true}.
     */
    private static final String REQUIRE_EXPLICIT_POLICY_ENABLED_MESSAGE =
            "jaxrs.security.requireExplicitPolicy is enabled: "
                    + "explicit security policies are required for every JAX-RS operation";

    /**
     * Provides {@link JaxRsConfig} by deserializing the {@code "jaxrs"} section of the
     * application configuration. Missing fields fall back to {@link JaxRsConfig} defaults
     * (base path {@code "/*"}, OpenAPI path {@code "openapi.json"}, strict operationId
     * matching, and {@code WARN} media type validation).
     *
     * <p>Before the section is parsed, a raw-key check on the {@code "jaxrs"} object rejects
     * three shapes, naming only the offending key names — never a configuration value:
     *
     * <ul>
     *   <li>a security-related key misplaced or miscased directly under {@code "jaxrs"} (for
     *       example {@code "Security"} or a {@link JaxRsSecurityConfig} component name such as
     *       {@code "requireExplicitPolicy"} appearing outside the {@code "security"}
     *       subsection);
     *   <li>a {@code "jaxrs.security"} value that is present but is not a JSON object, including
     *       an explicit JSON {@code null};
     *   <li>an unrecognized key under {@code "jaxrs.security"}, checked against
     *       {@link JaxRsSecurityConfig}'s record component names.
     * </ul>
     *
     * <p>After parsing, when {@link JaxRsSecurityConfig#requireExplicitPolicy()} resolves to
     * {@code true}, exactly one INFO line announces the opt-in; a missing section or a
     * {@code false} value logs nothing.
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the deserialized JAX-RS routing configuration
     * @throws ConfigurationException when the raw-key check rejects the {@code "jaxrs"} or
     *     {@code "jaxrs.security"} shape
     */
    @Provides
    @Singleton
    static JaxRsConfig jaxRsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject jaxrs = JsonConfigPaths.navigateObject(config, "jaxrs");
        checkSecurityKeys(jaxrs);
        JaxRsConfig result = parser.parse(jaxrs, JaxRsConfig.class);
        if (result.security().requireExplicitPolicy()) {
            log.info(REQUIRE_EXPLICIT_POLICY_ENABLED_MESSAGE);
        }
        return result;
    }

    /**
     * Rejects raw {@code "jaxrs"} keys that are misplaced or miscased security settings, and raw
     * {@code "jaxrs.security"} keys that are not a {@link JaxRsSecurityConfig} record component.
     * Only key names are read — no configuration value is inspected or echoed.
     *
     * @param jaxrs the {@code "jaxrs"} section, already navigated to a {@link JsonObject}
     * @throws ConfigurationException when a security-related key is misplaced or miscased under
     *     {@code "jaxrs"}, {@code "jaxrs.security"} is present but not a {@link JsonObject}, or
     *     {@code "jaxrs.security"} contains an unknown key
     */
    private static void checkSecurityKeys(JsonObject jaxrs) {
        Set<String> misplaced = new TreeSet<>();
        for (String key : jaxrs.fieldNames()) {
            boolean miscasedSectionName = key.equalsIgnoreCase("security") && !key.equals("security");
            boolean securityComponentName = SECURITY_COMPONENT_NAMES.stream().anyMatch(key::equalsIgnoreCase);
            if (miscasedSectionName || securityComponentName) {
                misplaced.add(key);
            }
        }
        if (!misplaced.isEmpty()) {
            throw new ConfigurationException("Misplaced or miscased security keys under 'jaxrs': "
                    + quoteJoin(misplaced)
                    + "; security settings belong under 'jaxrs.security'");
        }

        if (!jaxrs.containsKey("security")) {
            return;
        }
        if (!(jaxrs.getValue("security") instanceof JsonObject security)) {
            throw new ConfigurationException("'jaxrs.security' must be a JSON object");
        }
        Set<String> unknown = new TreeSet<>();
        for (String key : security.fieldNames()) {
            if (!SECURITY_COMPONENT_NAMES.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigurationException("Unknown keys under 'jaxrs.security': " + quoteJoin(unknown));
        }
    }

    /**
     * Formats a sorted key set as single-quoted, comma-separated names for a
     * {@link ConfigurationException} message.
     *
     * @param keys the sorted key names
     * @return the formatted, single-quoted, comma-joined key list
     */
    private static String quoteJoin(Set<String> keys) {
        return keys.stream().map(key -> "'" + key + "'").collect(Collectors.joining(", "));
    }

    /**
     * Provides {@link CorsConfig} by deserializing the {@code "cors"} section of the
     * application configuration. Missing fields fall back to {@link CorsConfig} defaults
     * (CORS disabled, wildcard origins, standard HTTP methods, all headers allowed).
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the deserialized CORS configuration
     */
    @Provides
    @Singleton
    static CorsConfig corsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "cors"), CorsConfig.class);
    }

    /**
     * Provides a {@link RouterCustomizer} that installs a Vert.x {@link CorsHandler} on all
     * routes before any sub-router mounts. When {@link CorsConfig#enabled()} is {@code false},
     * a no-op customizer is returned so the CORS handler is not installed.
     *
     * <p>The handler is added via {@code router.route().handler(cors)}, which covers all routes
     * including preflight {@code OPTIONS} requests, regardless of the path or HTTP method.
     *
     * @param config the CORS configuration
     * @return a {@link RouterCustomizer} that installs (or skips) the CORS handler
     */
    @Provides
    @IntoSet
    static RouterCustomizer corsCustomizer(CorsConfig config) {
        if (!config.enabled()) {
            return router -> {};
        }
        return router -> {
            CorsHandler cors = CorsHandler.create();
            config.origins().forEach(cors::addOrigin);
            config.allowedMethods().stream().map(HttpMethod::valueOf).forEach(cors::allowedMethod);
            config.allowedHeaders().forEach(cors::allowedHeader);
            config.exposedHeaders().forEach(cors::exposedHeader);
            cors.allowCredentials(config.allowCredentials());
            cors.maxAgeSeconds(config.maxAge());
            router.route().handler(cors);
        };
    }

    /**
     * Provides the {@link HttpConfig} by deserializing the {@code "http"} section of the
     * application configuration. Missing fields fall back to {@link HttpConfig} defaults.
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the deserialized HTTP server configuration
     */
    @Provides
    @Singleton
    static HttpConfig httpConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "http"), HttpConfig.class);
    }

    /**
     * Provides a fully-configured {@link HttpServerOptions} derived from {@link HttpConfig}.
     * Delegates all mapping logic to {@link HttpConfig#toHttpServerOptions()}.
     *
     * @param httpConfig the HTTP configuration
     * @return configured Vert.x HTTP server options
     */
    @Provides
    @Singleton
    static HttpServerOptions httpServerOptions(HttpConfig httpConfig) {
        return httpConfig.toHttpServerOptions();
    }

    // --- Standard middleware ---

    /**
     * Provides {@link RequestContextLifecycle} (ROOT, order={@code Integer.MIN_VALUE}).
     * Owns all per-request {@link dev.vertique.core.context.ContextHolder.Scope} cleanups and
     * {@code afterClose} tasks. Fires last among end handlers due to Vert.x Web's
     * reverse end-handler ordering.
     *
     * @param middleware the singleton lifecycle middleware
     * @return the lifecycle middleware contributed to the middleware set
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware requestContextLifecycle(RequestContextLifecycle middleware) {
        return middleware;
    }

    /**
     * Provides the contextual logging middleware (ROOT, order=0).
     * Binds {@code method} and {@code path} into MDC for structured logging via the request
     * lifecycle handle. Since PR1, request-id resolution and the {@code requestId} MDC entry
     * are owned by {@code CorrelationIngressMiddleware} (in
     * {@link dev.vertique.rest.core.correlation}).
     *
     * @return the contextual logging middleware
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware contextualLoggingMiddleware() {
        return new ContextualLoggingMiddleware();
    }

    /**
     * Provides the default headers middleware (ROOT, order=10).
     * Sets configurable security headers on all responses, driven by {@link JaxRsConfig#defaultHeaders()}.
     *
     * @param jaxRsConfig the JAX-RS configuration containing the header configuration
     * @return the default headers middleware
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware defaultHeadersMiddleware(JaxRsConfig jaxRsConfig) {
        return new DefaultHeadersMiddleware(jaxRsConfig.defaultHeaders());
    }

    /**
     * Provides the content type validation middleware (API, order=20).
     * Rejects POST/PUT/PATCH requests with unrecognized content types.
     * Accepts {@code application/*}, {@code multipart/form-data}, and {@code text/*}.
     *
     * @return the content type validation middleware
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware contentTypeValidationMiddleware() {
        return new ContentTypeValidationMiddleware();
    }

    /**
     * Provides {@link RestRequestCompletionEmitter} (ROOT, order={@link RequestContextLifecycle#ORDER}+5).
     * Emits exactly one {@link dev.vertique.rest.core.events.RestRequestCompletedEvent} per handled
     * request, covering all success and failure paths.
     *
     * @param emitter the singleton emitter middleware
     * @return the emitter contributed to the middleware set
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware restRequestCompletionEmitter(RestRequestCompletionEmitter emitter) {
        return emitter;
    }

    /**
     * Provides {@link OperationIdCaptureContributor} (priority=350).
     * Stores the OpenAPI {@code operationId} and route template on the routing context for each
     * operation-dispatched request so the emitter can include them in the completion event.
     *
     * @param contributor the singleton contributor
     * @return the contributor contributed to the operation handler contributor set
     */
    @Provides
    @IntoSet
    @Singleton
    static OperationHandlerContributor operationIdCaptureContributor(OperationIdCaptureContributor contributor) {
        return contributor;
    }
}
