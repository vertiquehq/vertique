// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.convert.ParamConversionException;
import dev.vertique.rest.core.convert.ParamConverterNotFoundException;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.dagger.RestCoreModule;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.sse.SseChannelFactory;
import dev.vertique.rest.jaxrs.application.ApiDocsModuleInstalled;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Dagger module providing the JAX-RS routing runtime: exception mapping,
 * response handling, and the default JAX-RS router mount.
 *
 * <p>Include this module in your application's Dagger component to get the full
 * REST framework with JAX-RS routing. It automatically includes {@link RestCoreModule}
 * for multibinding declarations and standard middleware, and
 * {@link dev.vertique.json.JsonRuntimeModule} so the {@link dev.vertique.core.json.JsonMapperProfileRegistry}
 * is available to resolve per-method request-body JSON profiles (FR-JSON-007B).
 *
 * <p>Example usage in a Dagger component:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, ...})
 * public interface AppComponent {
 *     HttpVerticle httpVerticle();
 * }
 * }</pre>
 */
@Slf4j
@Module(includes = {RestCoreModule.class, dev.vertique.json.JsonRuntimeModule.class})
public abstract class RestModule {

    // --- Multibinding declarations ---

    /**
     * Declares the empty {@link RestExceptionMapperCustomizer} multibinding set.
     * Applications contribute customizers via {@code @Provides @IntoSet RestExceptionMapperCustomizer}.
     *
     * @return an empty set (populated by Dagger from {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<RestExceptionMapperCustomizer> restExceptionMapperCustomizers();

    // Note: paramConverterProviders, paramConverterBindings, paramConverterRegistry, and
    // paramConversionResolver multibindings and providers were moved to RestCoreModule so that
    // the REST client module (which includes RestCoreModule) can access them without depending
    // on rest-jaxrs.

    /**
     * Declares the {@link RequestValidationStrategy} multibinding set. Every application includes this
     * module, so the set always contains at least the {@link NoneValidationStrategy} ({@code none});
     * the {@code web-validation} strategy is contributed by {@code vertique-rest-validation} when an
     * application depends on it. The selected strategy is resolved by its {@code id()} against this set
     * at router-build time (see
     * {@link dev.vertique.rest.jaxrs.validation.RequestValidationStrategySelector}).
     *
     * @return the request-validation strategy set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<RequestValidationStrategy> requestValidationStrategies();

    /**
     * Declares the {@link MountPublicationHook} multibinding set. Empty by default; sibling
     * framework modules contribute to it — the documentation module through
     * {@code @ElementsIntoSet}, and the OpenAPI-contract validation module's startup contract-load
     * check through {@code @IntoSet}. Every JAX-RS mount hands its completed publication to each
     * hook in this set once its router is built.
     *
     * @return the publication hook set (populated by {@code @ElementsIntoSet} and {@code @IntoSet}
     *     contributions)
     */
    @Multibinds
    abstract Set<MountPublicationHook> mountPublicationHooks();

    /**
     * Declares the empty {@link FileContentVerifier} multibinding set. Applications contribute
     * verifiers via {@code @Provides @IntoSet FileContentVerifier}.
     *
     * @return the file-content verifier set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<FileContentVerifier> fileContentVerifiers();

    /**
     * Declares the {@link GeneratedRestApplicationRegistration} multibinding set. A generated
     * module contributes one registration per {@code @RestApplication} declaration via
     * {@code @Provides @IntoSet}; the set stays empty when no declaration is present.
     *
     * @return the native application registration set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<GeneratedRestApplicationRegistration> generatedRestApplicationRegistrations();

    /**
     * Declares the {@link ApiDocsModuleInstalled} optional binding: present when the OpenAPI
     * documentation module is included in this component, absent otherwise. rest-jaxrs never
     * depends on the docs module, so it can only detect this marker, never bind it.
     *
     * @return the optional marker binding, resolved as {@code Optional<ApiDocsModuleInstalled>}
     */
    @BindsOptionalOf
    abstract ApiDocsModuleInstalled apiDocsModuleInstalled();

    /**
     * Provides the {@link RestApplications} view, built once per component by
     * {@link RestApplicationsBuilder} from every declared native registration, the parsed
     * {@code jaxrs.applications} configuration, the global {@code jaxrs.openapiPath} default, and
     * whether the OpenAPI documentation module is present in this component.
     *
     * <p>{@link #parseJaxRsApplications} runs first, before the builder's checks: a raw-key,
     * parse, or blank-value {@link ConfigurationException} it throws propagates unwrapped and
     * alone, never wrapped by the builder. No package-private type reaches the Dagger graph: the
     * parsed {@link RestApplicationConfig} list is a plain method-local value, never bound.
     *
     * @param registrations    the declared native application registration set (empty in
     *                         zero-declaration mode)
     * @param config           the full application configuration, navigated to {@code jaxrs} and
     *                         {@code jaxrs.applications} by {@link #parseJaxRsApplications}
     * @param parser           the injected config parser
     * @param jaxRsConfig      the JAX-RS routing configuration, supplying the global
     *                         {@code jaxrs.openapiPath} default
     * @param apiDocsModuleInstalled present when the OpenAPI documentation module is included in this
     *                         component
     * @return the component-scoped view
     */
    @Provides
    @Singleton
    static RestApplications restApplications(
            Set<GeneratedRestApplicationRegistration> registrations,
            @VertxConfig JsonObject config,
            ConfigParser parser,
            JaxRsConfig jaxRsConfig,
            Optional<ApiDocsModuleInstalled> apiDocsModuleInstalled) {
        List<RestApplicationConfig> configuredApplications = parseJaxRsApplications(config, parser);
        return RestApplicationsBuilder.build(
                registrations, configuredApplications, jaxRsConfig, apiDocsModuleInstalled);
    }

    /**
     * Contributes the rest-jaxrs {@link MountCompositionValidator}: it rejects an application mount
     * that conflicts with a hand-built JAX-RS mount or with another application mount in the same
     * mount set (as when compositions are merged), and rejects two operations on any JAX-RS mounts
     * that share an operationId without sharing the same owner, once one or more applications are
     * declared or any application mount is present.
     *
     * @param view       this composition's application view
     * @param config     the JAX-RS routing configuration, carrying the configured
     *                   {@code jaxrs.validationStrategy} id
     * @param strategies the registered request-validation strategies, resolved by id against
     *                   {@code config}'s configured id
     * @return the rest-jaxrs composition validator, contributed into
     *     {@code Set<MountCompositionValidator>}
     */
    @Provides
    @IntoSet
    static MountCompositionValidator jaxRsApplicationMountValidator(
            RestApplications view, JaxRsConfig config, Set<RequestValidationStrategy> strategies) {
        return new JaxRsApplicationMountValidator(view, config, strategies);
    }

    /**
     * Declares the {@link GeneratedJaxRsResourceEntry} multibinding set. A generated module
     * contributes one entry per DI-eligible JAX-RS resource via {@code @Provides @IntoSet};
     * the set stays empty when no generated module is present.
     *
     * @return the generated resource catalog set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<GeneratedJaxRsResourceEntry> generatedJaxRsResourceEntries();

    /**
     * Contributes the {@code none} {@link RequestValidationStrategy} into the strategy multibinding so
     * the set is never empty. The {@code none} strategy installs no validation gate.
     *
     * @param strategy the no-validation strategy
     * @return the {@code none} strategy as a {@link RequestValidationStrategy} set element
     */
    @Binds
    @IntoSet
    abstract RequestValidationStrategy noneValidationStrategy(NoneValidationStrategy strategy);

    /**
     * Binds the INTERNAL {@link SyntheticOperationInstaller} seam to its package-private implementation.
     *
     * <p>Consumed by sibling framework modules (starting with the OpenAPI documentation module) to
     * install a framework-owned route that runs exactly the chain an equally annotated JAX-RS
     * resource method gets.
     *
     * @param installer the package-private implementation
     * @return the bound {@link SyntheticOperationInstaller} seam
     */
    @Binds
    abstract SyntheticOperationInstaller syntheticOperationInstaller(DefaultSyntheticOperationInstaller installer);

    /**
     * Declares an optional binding for {@link OperationSchemaSource}.
     *
     * <p>When {@code vertique-rest-validation} (or another module providing an
     * {@link OperationSchemaSource}) is included in the Dagger component, the optional is populated and
     * the {@code web-validation} gate can synthesize per-operation schemas. When absent — e.g. under the
     * {@code none} strategy with no validation module on the classpath — the optional is empty.
     *
     * @return the optional {@link OperationSchemaSource} binding declaration
     */
    @BindsOptionalOf
    abstract OperationSchemaSource operationSchemaSource();

    /**
     * Declares an optional binding for {@link BeanValidator}.
     *
     * <p>When the {@code ValidationModule} from the {@code validation} module is included in the
     * Dagger component, the optional is populated and method parameter validation is active.
     * When absent, validation is silently skipped.
     *
     * @return the optional {@link BeanValidator} binding declaration
     */
    @BindsOptionalOf
    abstract BeanValidator beanValidator();

    /**
     * Declares an optional binding for {@link InputObjectProcessor}.
     *
     * <p>When a module providing an {@code InputObjectProcessor} is included in the Dagger
     * component, input canonicalization and sanitization are active.
     *
     * <p>When absent, an application whose routes declare no policies runs unaffected — but a route
     * that <em>does</em> declare canonicalization or sanitization, either through its own annotations
     * or on a parameter type's fields, <strong>fails startup</strong>: {@code JaxRsRouteRegistrar}
     * raises one aggregated {@code ConfigurationException} naming every such route. Declared
     * processing is never silently skipped, and there is no opt-out flag.
     *
     * @return the optional {@link InputObjectProcessor} binding declaration
     */
    @BindsOptionalOf
    abstract InputObjectProcessor inputObjectProcessor();

    /**
     * Declares an optional binding for {@link ActionRegistry}.
     *
     * <p>When {@code SecurityAuthzModule} (the framework authorization engine) is included in
     * the Dagger component, the optional is populated and {@code @RequiresAction} values are
     * validated against the registry at startup. When absent, any operation that declares
     * {@code @RequiresAction} fails startup (fail-closed) because the action gate cannot be enforced.
     *
     * @return the optional {@link ActionRegistry} binding declaration
     */
    @BindsOptionalOf
    abstract ActionRegistry actionRegistry();

    /**
     * Declares an optional binding for the core action {@link Authorizer}.
     *
     * <p>When {@code SecurityAuthzModule} (the framework authorization engine) is included in the
     * Dagger component, the optional is populated. The {@code Authorizer} is the function the
     * enforcement layer calls to decide the {@code @RequiresAction} gate. Because this binding and the
     * {@link #actionRegistry()} binding are separate optional seams, a non-default graph can have the
     * registry present while the {@code Authorizer} is absent; the route registrar then fails startup
     * for any operation declaring {@code @RequiresAction} (fail-closed) rather than failing closed
     * per-request when the gate evaluates (finding W2).
     *
     * @return the optional core {@link Authorizer} binding declaration
     */
    @BindsOptionalOf
    abstract Authorizer optionalAuthorizer();

    /**
     * Contributes the {@link JaxRsDefaultProfileValidator} into the {@code Set<ComposeValidator>}
     * multibinding so the {@code VALIDATE} startup phase forces its construction, failing fast when
     * {@code jaxrs.jsonProfile} names an unknown profile (FR-JSON-050).
     *
     * @param impl the JAX-RS per-boundary default-profile validator
     * @return the validator contributed into the compose-validator set
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator jaxRsDefaultProfileValidator(JaxRsDefaultProfileValidator impl) {
        return impl;
    }

    /**
     * Provides the framework's default exception mapper, pre-configured with mappings for:
     * <ul>
     *   <li>{@link WebApplicationException} — preserves an existing response entity if present,
     *       otherwise produces a {@link ProblemDetail} body with the embedded status code</li>
     *   <li>{@link ValidationException} (400) — framework input validation failures</li>
     *   <li>{@link TooManyRequestsException} (429) — rate/quota limit exceeded, with a
     *       {@code Retry-After} header when the exception carries one; registered explicitly so it
     *       outranks the inherited {@code BusinessRuleException}/{@link ValidationException} → 400
     *       fallback (mirrors {@link UnavailableException}'s 503 role)</li>
     *   <li>{@link ParamConversionException} (400) — inbound parameter value failed conversion to its
     *       declared type (FR-015-08a); registered explicitly so the default is self-documenting and
     *       independent of the {@link ValidationException} hierarchy fallback</li>
     *   <li>{@link ParamConverterNotFoundException} (500) — no converter/provider satisfies a declared
     *       parameter type at request time (a misconfiguration); registered explicitly rather than
     *       relying on the {@link Throwable} fallback. The exception's diagnostic message (parameter
     *       name and target type) is logged server-side; the {@link ProblemDetail} detail is the
     *       fixed client-safe {@code "Internal Server Error"}</li>
     *   <li>{@link IllegalArgumentException} (400) — third-party and application validation</li>
     *   <li>{@code dev.vertique.core.exception.UnauthorizedException} (401) — missing or invalid credentials</li>
     *   <li>{@code dev.vertique.core.exception.ForbiddenException} (403) — authenticated principal lacks permission</li>
     *   <li>{@link ConflictException} (409) — request conflicts with current resource state</li>
     *   <li>{@code dev.vertique.core.exception.NotFoundException} (404) — requested resource does not exist</li>
     *   <li>{@link UnavailableException} (503) — transient service/dependency unavailability</li>
     *   <li>{@link Throwable} (500) — fallback for all unhandled exceptions</li>
     * </ul>
     *
     * <p>Note: {@code VertiqueSecurityException} itself has no REST mapping. A bare instance
     * falls through to the {@link Throwable} (500) fallback.
     *
     * <p>Package-private: this provider serves the module's own Dagger wiring and same-package
     * tests only. Per ADR-0205, a test harness in a sibling REST-surface module does not call this
     * method directly; instead it declares its own package-private {@code @Component} over {@code
     * RestTestFixtureModule} (plus {@code RestTestNoSecurityModule} or {@code AuthModule}, the way
     * {@code vertique-rest-test}'s module docs show {@code ValidationMountComponent} doing) and lets
     * that graph resolve the framework's real default mapping, rather than duplicating this
     * hierarchy or falling back to an incomplete stand-in.
     *
     * @return a {@link DefaultExceptionMapper} with built-in hierarchy-aware handlers
     */
    @Provides
    @Singleton
    static DefaultExceptionMapper defaultExceptionMapper() {
        return new DefaultExceptionMapper()
                .on(WebApplicationException.class, ex -> {
                    Response original = ex.getResponse();
                    if (original.getEntity() != null) {
                        return original;
                    }
                    return Response.status(original.getStatus())
                            .entity(ProblemDetail.of(original.getStatus(), ex.getMessage()))
                            .type("application/problem+json")
                            .build();
                })
                .on(RestValidationException.class, ex -> Response.status(400)
                        .entity(ValidationProblemDetail.of(ex.getMessage(), ex.errors()))
                        .type("application/problem+json")
                        .build())
                .on(BeanValidationException.class, ex -> {
                    List<ValidationErrorDetail> errors = ex.violations().stream()
                            .map(v -> new ValidationErrorDetail(v.path(), v.message(), null, v.type(), v.args()))
                            .toList();
                    return Response.status(400)
                            .entity(ValidationProblemDetail.of(ex.getMessage(), errors))
                            .type("application/problem+json")
                            .build();
                })
                .on(ValidationException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(TooManyRequestsException.class, ex -> {
                    Response.ResponseBuilder builder = Response.status(429)
                            .entity(ProblemDetail.of(429, ex.getMessage()))
                            .type("application/problem+json");
                    // max(1, ceil(millis / 1000)) — same rounding rule vertique-rest-rate-limit's
                    // RateLimitHttpMapping.retryAfterSeconds applies, so an app-level TooManyRequestsException
                    // and a rate-limit denial never disagree on how a sub-second hint rounds.
                    ex.retryAfter().ifPresent(retryAfter -> {
                        long millis = Math.max(0L, retryAfter.toMillis());
                        long seconds = Math.max(1L, Math.ceilDiv(millis, 1000L));
                        builder.header("Retry-After", Long.toString(seconds));
                    });
                    return builder.build();
                })
                .on(ParamConversionException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(ParamConverterNotFoundException.class, ex -> {
                    // Wiring gap: keep param name / target type for operators, never for clients
                    // (OWASP A02:2025 / CWE-209).
                    log.error("ParamConverter not found for a declared parameter type", ex);
                    return Response.status(500)
                            .entity(ProblemDetail.of(500, "Internal Server Error"))
                            .type("application/problem+json")
                            .build();
                })
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(dev.vertique.core.exception.UnauthorizedException.class, ex -> Response.status(401)
                        .entity(ProblemDetail.of(401, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(dev.vertique.core.exception.ForbiddenException.class, ex -> Response.status(403)
                        .entity(ProblemDetail.of(403, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(ConflictException.class, ex -> Response.status(409)
                        .entity(ProblemDetail.of(409, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(dev.vertique.core.exception.NotFoundException.class, ex -> Response.status(404)
                        .entity(ProblemDetail.of(404, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(UnavailableException.class, ex -> Response.status(503)
                        .entity(ProblemDetail.of(503, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(Throwable.class, ex -> {
                    log.error("Unhandled exception", ex);
                    return Response.status(500)
                            .entity(ProblemDetail.of(500, "Internal Server Error"))
                            .type("application/problem+json")
                            .build();
                });
    }

    /**
     * Provides the {@link ExceptionMapperRegistry}, combining the framework's
     * {@link DefaultExceptionMapper} with user-contributed mappers from the
     * {@code Set<ExceptionMapper<?>>} multibinding.
     *
     * @param defaults the framework default exception mapper
     * @param mappers  the set of user-contributed exception mappers
     * @return a fully configured {@link ExceptionMapperRegistry}
     */
    @Provides
    @Singleton
    static ExceptionMapperRegistry exceptionMapperRegistry(
            DefaultExceptionMapper defaults, Set<ExceptionMapper<?>> mappers) {
        return new ExceptionMapperRegistry(defaults, mappers);
    }

    /**
     * Provides the {@link RestExceptionMapper} singleton assembled from all registered
     * {@link RestExceptionMapperCustomizer} contributions.
     *
     * <p>Customizers are applied in {@link OrderedExtension} order (phase, then priority, then
     * orderKey). Because they are applied in sorted order and a later customizer overrides an
     * earlier one for the same exception type (last-wins semantics), a {@code SYSTEM_LAST}
     * customizer applies last and wins regardless of its numeric priority.
     *
     * @param customizers the set of customizers contributed via multibinding
     * @return the fully configured {@link RestExceptionMapper}
     */
    @Provides
    @Singleton
    static RestExceptionMapper restExceptionMapper(Set<RestExceptionMapperCustomizer> customizers) {
        RestExceptionMapper mapper = new RestExceptionMapper();
        customizers.stream().sorted(OrderedExtension.comparator()).forEach(c -> c.customize(mapper));
        return mapper;
    }

    /**
     * Sorts the request interceptors once in {@link OrderedExtension} order for reuse by the
     * serializer and handler.
     *
     * @param interceptors the set of request interceptors contributed via multibinding
     * @return an unmodifiable list of interceptors in OrderedExtension order (phase, priority, orderKey)
     */
    @Provides
    @Singleton
    static List<RequestInterceptor> sortedRequestInterceptors(Set<RequestInterceptor> interceptors) {
        return interceptors.stream().sorted(OrderedExtension.comparator()).toList();
    }

    /**
     * Sorts the body encoders once in {@link OrderedExtension} order (phase, priority, then orderKey
     * for determinism) so the serializer can iterate in stable order.
     *
     * @param encoders the set of response body encoders contributed via multibinding
     * @return an unmodifiable list of encoders in OrderedExtension order (phase, priority, orderKey)
     */
    @Provides
    @Singleton
    static List<ResponseBodyEncoder> sortedResponseBodyEncoders(Set<ResponseBodyEncoder> encoders) {
        return encoders.stream().sorted(OrderedExtension.comparator()).toList();
    }

    /**
     * Provides the {@link ResponseSerializer} that writes JAX-RS {@link Response} objects to
     * the HTTP wire. Body encoding is delegated to the first matching {@link ResponseBodyEncoder}
     * from the {@link OrderedExtension}-sorted list. The serializer invokes all
     * {@link RequestInterceptor#onSerialize} hooks after encoding for observability.
     *
     * @param sortedInterceptors the request interceptors sorted by {@link OrderedExtension#comparator()}
     * @param sortedEncoders     the body encoders sorted by {@link OrderedExtension#comparator()}
     * @return a {@link DefaultResponseSerializer} wired with interceptors and encoders
     */
    @Provides
    @Singleton
    static ResponseSerializer responseSerializer(
            List<RequestInterceptor> sortedInterceptors, List<ResponseBodyEncoder> sortedEncoders) {
        return new DefaultResponseSerializer(sortedInterceptors, sortedEncoders);
    }

    // --- Default ResponseBodyEncoder contributions ---

    /**
     * Contributes the {@link BufferBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Handles {@link io.vertx.core.buffer.Buffer} entities (pre-serialized).
     *
     * @return a {@link BufferBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder bufferBodyEncoder() {
        return new BufferBodyEncoder();
    }

    /**
     * Contributes the {@link ByteArrayBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Handles {@code byte[]} entities with {@code application/octet-stream}.
     *
     * @return a {@link ByteArrayBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder byteArrayBodyEncoder() {
        return new ByteArrayBodyEncoder();
    }

    /**
     * Contributes the {@link StringBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Handles {@link String} entities with {@code text/plain}.
     *
     * @return a {@link StringBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder stringBodyEncoder() {
        return new StringBodyEncoder();
    }

    /**
     * Contributes the {@link ReadStreamBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Handles {@link io.vertx.core.streams.ReadStream} entities via streaming pipe.
     *
     * @return a {@link ReadStreamBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder readStreamBodyEncoder() {
        return new ReadStreamBodyEncoder();
    }

    /**
     * Contributes the {@link JsonBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Catch-all fallback that serializes any entity to JSON
     * with {@code application/json}. Runs last (priority {@code 1100}).
     *
     * @return a {@link JsonBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder jsonBodyEncoder() {
        return new JsonBodyEncoder();
    }

    /**
     * Contributes the {@link SseBodyEncoder} to the {@code Set<ResponseBodyEncoder>}
     * multibinding. Handles {@link io.vertx.core.streams.ReadStream} entities with
     * {@code text/event-stream} content type by formatting events as SSE wire protocol frames.
     * Runs before {@link ReadStreamBodyEncoder} (priority {@code 999}).
     *
     * @param jaxRsConfig the JAX-RS configuration providing SSE settings
     * @return a {@link SseBodyEncoder} instance
     */
    @Provides
    @IntoSet
    static ResponseBodyEncoder sseBodyEncoder(JaxRsConfig jaxRsConfig) {
        return new SseBodyEncoder(jaxRsConfig.sse());
    }

    /**
     * Provides the {@link SseChannelFactory} singleton for creating per-request SSE channels.
     * Application code injects this factory and calls {@link SseChannelFactory#create()} inside
     * resource methods annotated with {@code @Produces("text/event-stream")}.
     *
     * @param vertx       the Vert.x instance used when constructing channels
     * @param jaxRsConfig the JAX-RS configuration providing SSE settings
     * @return a singleton {@link SseChannelFactory}
     */
    @Provides
    @Singleton
    static SseChannelFactory sseChannelFactory(io.vertx.core.Vertx vertx, JaxRsConfig jaxRsConfig) {
        return new DefaultSseChannelFactory(vertx, jaxRsConfig.sse());
    }

    // --- RequestBodyDecoder providers ---

    /**
     * Sorts the request body decoders once in {@link OrderedExtension} order
     * (phase → priority → orderKey) so the invoker can iterate in stable order.
     *
     * @param decoders the set of request body decoders contributed via multibinding
     * @return an unmodifiable list of decoders sorted by {@link OrderedExtension#comparator()}
     */
    @Provides
    @Singleton
    static List<RequestBodyDecoder> sortedRequestBodyDecoders(Set<RequestBodyDecoder> decoders) {
        return decoders.stream().sorted(OrderedExtension.comparator()).toList();
    }

    /**
     * Contributes the {@link JsonRequestBodyDecoder} to the {@code Set<RequestBodyDecoder>}
     * multibinding. Catch-all fallback that deserializes JSON bodies to POJOs.
     * Runs last (priority {@code 1100}).
     *
     * @return a {@link JsonRequestBodyDecoder} instance
     */
    @Provides
    @IntoSet
    static RequestBodyDecoder jsonRequestBodyDecoder() {
        return new JsonRequestBodyDecoder();
    }

    /**
     * Contributes the {@link TextRequestBodyDecoder} to the {@code Set<RequestBodyDecoder>}
     * multibinding. Handles {@code text/*} bodies as {@link String}.
     *
     * @return a {@link TextRequestBodyDecoder} instance
     */
    @Provides
    @IntoSet
    static RequestBodyDecoder textRequestBodyDecoder() {
        return new TextRequestBodyDecoder();
    }

    /**
     * Contributes the {@link BinaryRequestBodyDecoder} to the {@code Set<RequestBodyDecoder>}
     * multibinding. Handles {@code application/octet-stream} bodies as {@link io.vertx.core.buffer.Buffer}
     * or {@code byte[]}.
     *
     * @return a {@link BinaryRequestBodyDecoder} instance
     */
    @Provides
    @IntoSet
    static RequestBodyDecoder binaryRequestBodyDecoder() {
        return new BinaryRequestBodyDecoder();
    }

    /**
     * Contributes the {@link FormUrlencodedRequestBodyDecoder} to the {@code Set<RequestBodyDecoder>}
     * multibinding. Handles {@code application/x-www-form-urlencoded} bodies as POJOs.
     *
     * @return a {@link FormUrlencodedRequestBodyDecoder} instance
     */
    @Provides
    @IntoSet
    static RequestBodyDecoder formUrlencodedRequestBodyDecoder() {
        return new FormUrlencodedRequestBodyDecoder();
    }

    /**
     * Registers the JAX-RS router mount(s): a default mount built from the {@link JaxRsResources}
     * multibinding set in zero-declaration mode, or one mount per active application, built by
     * {@link JaxRsApplicationComposer}, when one or more {@code @RestApplication} registrations are
     * declared.
     *
     * <p>Requests {@code view} on both branches, so its checks (duplicate, reserved, or mismatched
     * names, sole discovery, and an unknown {@code jaxrs.applications.<name>}) run even in
     * zero-declaration mode, before {@code resources} or {@code catalog} resolves. With an empty
     * {@code registrations} set, this provider keeps today's zero-declaration behavior unchanged: a
     * single default mount is built directly from {@code @JaxRsResources}, at {@code jaxrs.basePath}.
     * For single-API apps, resources are contributed via {@code @Provides @IntoSet @JaxRsResources}
     * in the app's {@code ResourceModule}.
     *
     * <p>For multi-API apps that wire their own mounts via the factory, leave
     * {@code @JaxRsResources} empty — no default mount is contributed, avoiding
     * spurious overlap warnings and mount customizer invocations.
     *
     * <p>With one or more registrations, this provider delegates entirely to
     * {@link JaxRsApplicationComposer#compose}: the routing base path no longer applies to any
     * mount, and disabling every declared application never falls back to the default mount.
     * {@code @JaxRsResources} holds every manual contribution; a resource generated by the current
     * processor reaches an application mount only through the generated resource catalog, never
     * through {@code @JaxRsResources} directly.
     *
     * <p>Guards against re-entry on both branches: a manually contributed resource or a generated
     * resource catalog entry must not depend on THIS component's own {@code Set<RouterMount>}, since
     * resolving it recurses back into this same provider. Keyed on the identity of this component's
     * own {@code @Singleton JaxRsConfig} instance (never on the thread alone), so
     * {@link JaxRsApplicationComposer#enterComposition(JaxRsConfig)} rejects only re-entry into this
     * SAME component's composition and still fails named instead of overflowing the stack; a nested
     * composition of a different component, with its own distinct {@code JaxRsConfig} instance,
     * succeeds. The matching {@link JaxRsApplicationComposer#exitComposition(JaxRsConfig)} always
     * runs in {@code finally}, so only a call that actually entered ever clears its own component's
     * guard entry.
     *
     * @param factory       the JAX-RS router mount factory
     * @param view          this component's {@link RestApplications} view, requested here so its
     *                      checks run before either branch resolves a resource
     * @param registrations the declared native application registration set (empty in
     *                      zero-declaration mode)
     * @param resources     the {@code @JaxRsResources} instances, resolved lazily
     * @param catalog       the generated resource catalog, resolved lazily
     * @param config        the JAX-RS routing configuration (base path and OpenAPI spec location);
     *                      also this component's re-entry guard key, since it is
     *                      {@code @Singleton}-scoped to this component
     * @return one mount per active application in explicit mode; otherwise a singleton set with the
     *     default mount, or an empty set when there are no resources
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> jaxRsRouterMount(
            JaxRsRouterMount.Factory factory,
            RestApplications view,
            Set<GeneratedRestApplicationRegistration> registrations,
            @JaxRsResources Provider<Set<Object>> resources,
            Provider<Set<GeneratedJaxRsResourceEntry>> catalog,
            JaxRsConfig config) {
        JaxRsApplicationComposer.enterComposition(config);
        try {
            if (registrations.isEmpty()) {
                Set<Object> resolvedResources = resources.get();
                if (resolvedResources.isEmpty()) {
                    return Set.of();
                }
                return Set.of(factory.create(config.basePath(), config.openapiPath(), resolvedResources));
            }
            return JaxRsApplicationComposer.compose(factory, view, registrations, resources, catalog, config);
        } finally {
            JaxRsApplicationComposer.exitComposition(config);
        }
    }

    // --- jaxrs.applications configuration ---

    /**
     * Parses the strict keyed-object {@code jaxrs.applications} section into one
     * {@link RestApplicationConfig} per entry, in the section's order. Not a Dagger binding.
     *
     * <p>The section is checked in three steps. Steps 1 and 3 each fail with one aggregated message
     * that names every offending configuration path (or, for a miscased section, every offending
     * {@code jaxrs} key), sorted, and never a configuration value:
     * <ol>
     *   <li>A raw-key check, before any parsing: it first rejects any {@code jaxrs} key that equals
     *       {@code applications} ignoring case but is not spelled exactly {@code applications};
     *       then {@code jaxrs.applications}, when present, must be a JSON object ({@code null}
     *       included in the rejection); every entry {@code jaxrs.applications.<name>} must be a
     *       JSON object; and {@code openapiPath}, compared case-sensitively, is the only key an
     *       entry may carry.</li>
     *   <li>A keyed-object parse through {@link ConfigParser#parseKeyedObject(JsonObject, String,
     *       Class)}, which injects each entry's key into {@link RestApplicationConfig#name()}; an
     *       entry it cannot deserialize fails with the parser's own message.</li>
     *   <li>A blank-value check: a configured {@code openapiPath} that is empty or only whitespace
     *       fails naming {@code jaxrs.applications.<name>.openapiPath}. An absent key or an explicit
     *       JSON {@code null} binds {@code null} and is accepted.</li>
     * </ol>
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the parsed entries; empty when the section is absent or empty
     * @throws ConfigurationException when {@code jaxrs} or {@code jaxrs.applications} is present but
     *     not a JSON object, a {@code jaxrs} key equals {@code applications} ignoring case but is
     *     not spelled exactly {@code applications}, an entry is not a JSON object, an entry carries
     *     a key other than {@code openapiPath}, an entry cannot be parsed, or a configured
     *     {@code openapiPath} is blank
     */
    static List<RestApplicationConfig> parseJaxRsApplications(JsonObject config, ConfigParser parser) {
        JsonObject jaxrs = JsonConfigPaths.navigateObject(config, "jaxrs");
        checkApplicationKeys(jaxrs);
        List<RestApplicationConfig> applications = parser.parseKeyedObject(
                JsonConfigPaths.navigateObject(config, "jaxrs", "applications"), "name", RestApplicationConfig.class);
        checkBlankOpenapiPaths(applications);
        return applications;
    }

    /**
     * Rejects any {@code jaxrs} key that equals {@code applications} ignoring case but is not
     * spelled exactly {@code applications}, then a raw {@code jaxrs.applications} section that is
     * not a JSON object, entries that are not JSON objects, and entry keys other than
     * {@code openapiPath}. Reads only key names and whether each value is a JSON object; no
     * configuration value is echoed.
     *
     * @param jaxrs the {@code jaxrs} section, already navigated to a {@link JsonObject}
     * @throws ConfigurationException when a {@code jaxrs} key equals {@code applications} ignoring
     *     case but is not spelled exactly {@code applications}, when {@code jaxrs.applications} is
     *     present but not a JSON object, or when any entry is not a JSON object or carries a key
     *     other than {@code openapiPath}
     */
    private static void checkApplicationKeys(JsonObject jaxrs) {
        Set<String> miscased = new TreeSet<>();
        for (String key : jaxrs.fieldNames()) {
            if (key.equalsIgnoreCase("applications") && !key.equals("applications")) {
                miscased.add(key);
            }
        }
        if (!miscased.isEmpty()) {
            throw new ConfigurationException("Miscased applications keys under 'jaxrs': "
                    + quoteJoin(miscased)
                    + "; per-application settings belong under 'jaxrs.applications'");
        }
        if (!jaxrs.containsKey("applications")) {
            return;
        }
        if (!(jaxrs.getValue("applications") instanceof JsonObject applications)) {
            throw new ConfigurationException("'jaxrs.applications' must be a JSON object");
        }
        Set<String> invalid = new TreeSet<>();
        for (String name : applications.fieldNames()) {
            String entryPath = "jaxrs.applications." + name;
            if (!(applications.getValue(name) instanceof JsonObject entry)) {
                invalid.add(entryPath);
                continue;
            }
            for (String key : entry.fieldNames()) {
                if (!key.equals("openapiPath")) {
                    invalid.add(entryPath + "." + key);
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new ConfigurationException("Invalid entries or keys under 'jaxrs.applications': "
                    + quoteJoin(invalid)
                    + "; each entry must be a JSON object whose only key is 'openapiPath'");
        }
    }

    /**
     * Rejects every parsed entry whose configured {@code openapiPath} is empty or only whitespace,
     * naming each such path in one message. An absent or explicit-{@code null} {@code openapiPath}
     * is not blank.
     *
     * @param applications the parsed {@code jaxrs.applications} entries
     * @throws ConfigurationException when one or more entries carry a blank {@code openapiPath}
     */
    private static void checkBlankOpenapiPaths(List<RestApplicationConfig> applications) {
        Set<String> blank = new TreeSet<>();
        for (RestApplicationConfig application : applications) {
            String openapiPath = application.openapiPath();
            if (openapiPath != null && openapiPath.isBlank()) {
                blank.add("jaxrs.applications." + application.name() + ".openapiPath");
            }
        }
        if (!blank.isEmpty()) {
            throw new ConfigurationException("Blank values under 'jaxrs.applications': "
                    + quoteJoin(blank)
                    + "; set 'openapiPath' to a non-blank location or omit it");
        }
    }

    /**
     * Formats a sorted path set as single-quoted, comma-separated paths for a
     * {@link ConfigurationException} message.
     *
     * @param paths the sorted configuration paths
     * @return the formatted, single-quoted, comma-joined path list
     */
    private static String quoteJoin(Set<String> paths) {
        return paths.stream().map(path -> "'" + path + "'").collect(Collectors.joining(", "));
    }
}
