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
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.sse.SseChannelFactory;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.List;
import java.util.Set;
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
     * Declares the empty {@link FileContentVerifier} multibinding set. Applications contribute
     * verifiers via {@code @Provides @IntoSet FileContentVerifier}.
     *
     * @return the file-content verifier set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<FileContentVerifier> fileContentVerifiers();

    /**
     * Declares the {@link GeneratedJaxRsApplicationRegistration} multibinding set. A generated
     * module contributes one registration per eligible {@code jakarta.ws.rs.core.Application} via
     * {@code @Provides @IntoSet}; the set stays empty in zero-declaration mode.
     *
     * @return the application registration set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<GeneratedJaxRsApplicationRegistration> generatedJaxRsApplicationRegistrations();

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
     *       relying on the {@link Throwable} fallback</li>
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
                .on(ParamConverterNotFoundException.class, ex -> Response.status(500)
                        .entity(ProblemDetail.of(500, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
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
     * {@link JaxRsApplicationComposer}, when one or more {@code jakarta.ws.rs.core.Application}
     * registrations are declared.
     *
     * <p>Reads {@code applications} before it resolves {@code resources} or {@code catalog}, so
     * declaration presence is known before any generated resource provider runs. With an empty
     * {@code applications} set, this provider keeps today's zero-declaration behavior unchanged: a
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
     * mount, {@code @JaxRsResources} holds only the manual contributions no application selected,
     * and disabling every declared application never falls back to the default mount.
     *
     * @param factory      the JAX-RS router mount factory
     * @param applications the generated application registration set (empty in zero-declaration
     *                     mode)
     * @param resources    the {@code @JaxRsResources} instances, resolved lazily
     * @param catalog      the generated resource catalog, resolved lazily
     * @param config       the JAX-RS routing configuration (base path and OpenAPI spec location)
     * @return one mount per active application in explicit mode; otherwise a singleton set with the
     *     default mount, or an empty set when there are no resources
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> jaxRsRouterMount(
            JaxRsRouterMount.Factory factory,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            @JaxRsResources Provider<Set<Object>> resources,
            Provider<Set<GeneratedJaxRsResourceEntry>> catalog,
            JaxRsConfig config) {
        if (applications.isEmpty()) {
            Set<Object> resolvedResources = resources.get();
            if (resolvedResources.isEmpty()) {
                return Set.of();
            }
            return Set.of(factory.create(config.basePath(), config.openapiPath(), resolvedResources));
        }
        return JaxRsApplicationComposer.compose(factory, applications, resources, catalog, config);
    }
}
