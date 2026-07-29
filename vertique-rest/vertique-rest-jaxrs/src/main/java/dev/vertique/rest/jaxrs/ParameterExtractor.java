// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.request.EffectiveInputPolicies;
import dev.vertique.rest.core.request.InputObjectProcessor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestPreconditions;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.FormFieldEntityPart;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry;
import dev.vertique.rest.jaxrs.runtime.VertxFileUploadEntityPart;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts method arguments from a validated HTTP request based on JAX-RS parameter
 * annotations. Handles path, query, header, cookie, form, body, bean param, and
 * context injection.
 *
 * <p>Instances are stateless beyond their constructor-injected collaborators; the
 * {@link #BEAN_PARAM_CACHE} is a static JVM-wide cache keyed by bean type.
 */
@Slf4j
final class ParameterExtractor {

    /** Pre-computed mapping of a bean param field or record component to its parameter metadata. */
    private record BeanFieldEntry(String name, ResourceMethodMeta.ParamMeta meta) {}

    /**
     * Cache of pre-computed bean param field metadata, keyed by the bean type.
     * Populated lazily on first use; eliminates per-request reflection overhead.
     */
    private static final Map<Class<?>, List<BeanFieldEntry>> BEAN_PARAM_CACHE = new ConcurrentHashMap<>();

    private final ResourceMethodMeta meta;
    private final List<RequestBodyDecoder> decoders;
    private final RestContextResolution restContextResolution;
    private final @Nullable InputObjectProcessor objectProcessor;

    /**
     * The framework conversion resolver used to coerce every inbound scalar (and collection element)
     * to its declared Java type, so the reflective dispatch path, the binding facade, and the outbound
     * client share one symmetric conversion chain.
     */
    private final ParamConversionResolver paramConversionResolver;
    /**
     * Cached effective input policies, indexed by parameter position. Populated once at
     * construction by meta-annotation-aware resolution of
     * {@code @Canonicalize}/{@code @Sanitize}/{@code @Skip*} over the parameter's annotation array,
     * sourced from each {@link ResourceMethodMeta.ParamMeta}'s composed
     * {@link dev.vertique.core.codegen.ParameterMetadata} view (via {@code annotationsLazy()}) so the
     * per-request extract path never re-resolves them. Resolution goes through
     * {@link AnnotationResolver#findMetaAnnotation(List, Class)} so composed/aliased policy annotations
     * (e.g. a custom annotation meta-annotated with {@code @Canonicalize}) are honored exactly as on the
     * route-level path. Generated execution plans bypass this cache because they pass policies directly
     * to the {@code GeneratedJaxRsSupport} helpers; this cache exists for the reflective fallback path.
     */
    private final EffectiveInputPolicies[] cachedParamPolicies;

    /**
     * Precomputed declaring class name for the resource method, passed to
     * {@link RestContextResolution#require} on every CONTEXT parameter extraction. Computed once
     * at construction to avoid repeated {@code Class.getName()} calls on the hot path.
     */
    private final String declaringClassName;

    /**
     * Precomputed resource method name, passed to {@link RestContextResolution#require} on every
     * CONTEXT parameter extraction. Computed once at construction to avoid repeated
     * {@code Method.getName()} calls on the hot path.
     */
    private final String resourceMethodName;

    /**
     * Per-bean-type cache of effective input policies for {@code @BeanParam} field arrays
     * passed through {@link #materializeBean}. The route-level baseline is constant for the
     * lifetime of this {@code ParameterExtractor} instance (one per route), so caching by
     * {@code Class<?>} is sound: the same bean type always produces the same per-field policy
     * array under the same route baseline. Without this cache, a route that materialises a
     * bean with N fields on every request would re-walk N annotation lists per request.
     */
    private final Map<Class<?>, EffectiveInputPolicies[]> beanFieldPoliciesCache = new ConcurrentHashMap<>();

    /**
     * Route-scoped cache of the scalar {@link ConversionContext} for each parameter, keyed by the
     * {@link ResourceMethodMeta.ParamMeta} instance. Populated lazily on the hot path so a fresh
     * {@code ConversionContext} (and its {@code Annotation[]} closure) is allocated at most once per
     * parameter for the lifetime of this per-route extractor instead of per request. The same
     * {@code ParamMeta} instance recurs across requests (route params are reused; bean-param fields are
     * cached in {@link #BEAN_PARAM_CACHE}), so {@code computeIfAbsent} deduplicates by identity-equal
     * keys.
     */
    private final Map<ResourceMethodMeta.ParamMeta, ConversionContext> scalarContextCache = new ConcurrentHashMap<>();

    /**
     * Route-scoped cache of the per-element {@link ConversionContext} for each collection-valued
     * parameter, keyed by the {@link ResourceMethodMeta.ParamMeta} instance. Mirrors
     * {@link #scalarContextCache} for the {@link #coerceCollection} path.
     */
    private final Map<ResourceMethodMeta.ParamMeta, ConversionContext> componentContextCache =
            new ConcurrentHashMap<>();

    /**
     * Creates a new {@code ParameterExtractor} for the given resource method.
     * Input object processing is disabled when using this constructor.
     *
     * @param meta                   metadata describing the JAX-RS resource method
     * @param decoders               priority-sorted list of request body decoders
     * @param restContextResolution  coordinator for the {@link RestContextResolution} resolver chain;
     *                               must not be {@code null}
     */
    ParameterExtractor(
            ResourceMethodMeta meta, List<RequestBodyDecoder> decoders, RestContextResolution restContextResolution) {
        this(meta, decoders, restContextResolution, null, ConversionContexts.defaultResolver());
    }

    /**
     * Creates a new {@code ParameterExtractor} for the given resource method with optional
     * input processing support, coercing scalars through the built-ins-only default resolver.
     *
     * @param meta                   metadata describing the JAX-RS resource method
     * @param decoders               priority-sorted list of request body decoders
     * @param restContextResolution  coordinator for the {@link RestContextResolution} resolver chain;
     *                               must not be {@code null}
     * @param objectProcessor        optional input object processor for canonicalization and sanitization;
     *                               {@code null} disables processing
     */
    ParameterExtractor(
            ResourceMethodMeta meta,
            List<RequestBodyDecoder> decoders,
            RestContextResolution restContextResolution,
            @Nullable InputObjectProcessor objectProcessor) {
        this(meta, decoders, restContextResolution, objectProcessor, ConversionContexts.defaultResolver());
    }

    /**
     * Creates a new {@code ParameterExtractor} for the given resource method with optional input
     * processing support and an explicit {@link ParamConversionResolver}. Used by the route-registration
     * path so application converter bindings and JAX-RS providers participate in coercion.
     *
     * @param meta                    metadata describing the JAX-RS resource method
     * @param decoders                priority-sorted list of request body decoders
     * @param restContextResolution   coordinator for the {@link RestContextResolution} resolver chain;
     *                                must not be {@code null}
     * @param objectProcessor         optional input object processor for canonicalization and
     *                                sanitization; {@code null} disables processing
     * @param paramConversionResolver the framework conversion resolver used to coerce inbound scalars
     *                                and collection elements; must not be {@code null}
     */
    ParameterExtractor(
            ResourceMethodMeta meta,
            List<RequestBodyDecoder> decoders,
            RestContextResolution restContextResolution,
            @Nullable InputObjectProcessor objectProcessor,
            ParamConversionResolver paramConversionResolver) {
        this.meta = meta;
        this.decoders = decoders;
        this.restContextResolution = restContextResolution;
        this.objectProcessor = objectProcessor;
        this.paramConversionResolver = paramConversionResolver;
        this.cachedParamPolicies = computeCachedParamPolicies(meta);
        // Precomputed once for the FR-REST-174 missing-context diagnostic (used only on the
        // exceptional CONTEXT-resolution-failure path). Null-safe: some test fixtures build a
        // ResourceMethodMeta without a reflective method; such metas never carry CONTEXT params.
        Method resourceMethod = meta.method();
        this.declaringClassName =
                resourceMethod != null ? resourceMethod.getDeclaringClass().getName() : null;
        this.resourceMethodName = resourceMethod != null ? resourceMethod.getName() : null;
    }

    /**
     * Precomputes effective input policies for each parameter at construction time so the
     * per-request extract path never re-resolves them.
     *
     * @param meta the resource method metadata
     * @return policies indexed by parameter position
     */
    private static EffectiveInputPolicies[] computeCachedParamPolicies(ResourceMethodMeta meta) {
        List<ResourceMethodMeta.ParamMeta> params = meta.params();
        EffectiveInputPolicies[] cache = new EffectiveInputPolicies[params.size()];
        List<Class<? extends Canonicalizer>> routeCanon = meta.routeCanonicalizerChain();
        List<Class<? extends Sanitizer>> routeSanit = meta.routeSanitizerChain();
        for (int i = 0; i < params.size(); i++) {
            cache[i] = resolveParamPolicies(params.get(i), routeCanon, routeSanit);
        }
        return cache;
    }

    /**
     * Resolves effective input policies for a single parameter from the supplied route-level
     * baseline and the parameter's own annotations. The annotation array is sourced from the composed
     * {@link dev.vertique.core.codegen.ParameterMetadata} view (via {@code annotationsLazy()}), but each
     * policy annotation is resolved meta-annotation-aware through
     * {@link AnnotationResolver#findMetaAnnotation(List, Class)} so composed/aliased policy annotations
     * (a custom annotation itself meta-annotated with {@code @Canonicalize}/{@code @Sanitize}) are honored
     * — preserving the parameter-level policy behavior exactly. Used at construction time to populate the
     * per-instance cache.
     */
    private static EffectiveInputPolicies resolveParamPolicies(
            ResourceMethodMeta.ParamMeta pm,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit) {
        List<Class<? extends Canonicalizer>> canonChain = routeCanon;
        List<Class<? extends Sanitizer>> sanitChain = routeSanit;

        // Meta-annotation-aware resolution over the parameter's annotation array. The array is sourced
        // from the composed ParameterMetadata view (annotationsLazy() — never null; empty array when no
        // annotations were captured); findMetaAnnotation walks composed/aliased annotations so a custom
        // annotation meta-annotated with @Canonicalize/@Sanitize resolves the policy exactly as a direct
        // marker would. This preserves the parameter-level policy behavior (NFR-015-06).
        List<Annotation> annList = List.of(pm.annotationsLazy().get());

        SkipCanonicalization skipCanon = AnnotationResolver.findMetaAnnotation(annList, SkipCanonicalization.class);
        if (skipCanon != null) {
            canonChain = List.of();
        } else {
            Canonicalize canon = AnnotationResolver.findMetaAnnotation(annList, Canonicalize.class);
            if (canon != null) canonChain = List.of(canon.value());
        }

        SkipSanitization skipSanit = AnnotationResolver.findMetaAnnotation(annList, SkipSanitization.class);
        if (skipSanit != null) {
            sanitChain = List.of();
        } else {
            Sanitize sanit = AnnotationResolver.findMetaAnnotation(annList, Sanitize.class);
            if (sanit != null) sanitChain = List.of(sanit.value());
        }

        return new EffectiveInputPolicies(canonChain, sanitChain);
    }

    // --- Argument extraction entry point ---

    /**
     * Extracts method arguments from the bound request and {@link RoutingContext}.
     *
     * <p>This is the reflective dispatch path (FR-024): parameter and body values come from the
     * transport-neutral {@link BoundRequest} (which binds them from the raw Vert.x request) rather
     * than from a Vert.x {@code ValidatedRequest}.
     *
     * @param ctx          the current routing context
     * @param boundRequest the bound request exposing parameters and body as {@link RequestValue}s
     * @return array of arguments to pass to the resource method
     */
    Object[] extractArguments(RoutingContext ctx, BoundRequest boundRequest) {
        Object[] args = new Object[meta.params().size()];
        for (int i = 0; i < meta.params().size(); i++) {
            ResourceMethodMeta.ParamMeta pm = meta.params().get(i);
            args[i] = switch (pm.source()) {
                // FR-REST-166/167/179: all @Context / auto-injectable params go through the
                // resolver chain, including SecurityContext and ContextValue subtypes.
                case CONTEXT -> restContextResolution.require(pm.type(), ctx, declaringClassName, resourceMethodName);
                case PRECONDITIONS -> RequestPreconditions.from(ctx);
                case BODY -> deserializeBody(boundRequest.body(), pm.type(), pm.genericType(), ctx, pm);
                case FORM -> extractFormParam(pm, ctx);
                case FILE_UPLOADS -> List.copyOf(ctx.fileUploads());
                case ENTITY_PARTS -> extractAllEntityParts(ctx);
                case BEAN_PARAM -> extractBeanParam(pm.type(), boundRequest, ctx);
                default -> extractParam(pm, boundRequest);
            };
        }
        return args;
    }

    // --- Context resolution ---

    /**
     * Resolves a context parameter of the given {@code declaredType} through the
     * {@link RestContextResolution} chain, throwing
     * {@link dev.vertique.rest.core.context.RestContextUnavailableException} if no resolver can
     * supply a value (FR-REST-185).
     *
     * <p>Promoted to package-private so that {@link dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport}
     * can expose it to generated execution plans without duplicating the logic.
     *
     * @param declaredType  the declared Java type of the context parameter; must not be {@code null}
     * @param ctx           the current Vert.x routing context; must not be {@code null}
     * @param resourceClass the simple or qualified name of the JAX-RS resource class; must not be
     *                      {@code null}
     * @param methodName    the name of the resource method; must not be {@code null}
     * @return the resolved context value; never {@code null}
     * @throws dev.vertique.rest.core.context.RestContextUnavailableException if no resolver can
     *                                                                          supply the value
     */
    Object resolveContext(Class<?> declaredType, RoutingContext ctx, String resourceClass, String methodName) {
        return restContextResolution.require(declaredType, ctx, resourceClass, methodName);
    }

    // --- Scalar parameter extraction ---

    /**
     * Extracts a single parameter value from the bound request by consulting the appropriate
     * parameter map (path, query, header, or cookie). Policies are derived from
     * {@code paramMeta.annotations()} at call time via {@link #resolveParamPolicies}.
     *
     * @param paramMeta     metadata describing the parameter source and type
     * @param boundRequest  the bound request exposing parameter values as {@link RequestValue}s
     * @return the extracted parameter value, or {@code null} if absent
     */
    private Object extractParam(ResourceMethodMeta.ParamMeta paramMeta, BoundRequest boundRequest) {
        return extractScalarParam(paramMeta, resolveParamPolicies(paramMeta), boundRequest);
    }

    /**
     * Extracts a single parameter value from the bound request using a precomputed
     * {@link EffectiveInputPolicies} argument, bypassing per-call annotation re-resolution. This is
     * the reflective dispatch path (FR-024): values come from {@link BoundRequest}'s
     * {@link RequestValue} maps.
     *
     * @param paramMeta    metadata describing the parameter source and type
     * @param policies     precomputed effective input policies for this parameter
     * @param boundRequest the bound request exposing parameter values as {@link RequestValue}s
     * @return the extracted parameter value, or {@code null} if absent
     */
    Object extractScalarParam(
            ResourceMethodMeta.ParamMeta paramMeta, EffectiveInputPolicies policies, BoundRequest boundRequest) {
        Map<String, RequestValue> paramMap =
                switch (paramMeta.source()) {
                    case PATH -> boundRequest.pathParameters();
                    case QUERY -> boundRequest.query();
                    case HEADER -> boundRequest.headers();
                    case COOKIE -> boundRequest.cookies();
                    default -> throw new IllegalStateException("Unexpected source: " + paramMeta.source());
                };
        return extractScalarValue(paramMeta, policies, lookup(paramMap, paramMeta));
    }

    /**
     * Shared scalar extraction logic operating on a {@link RequestValue}, used by both the
     * reflective and generated paths (both now read from a {@link BoundRequest}) so the two
     * never diverge. Applies the {@code @DefaultValue}-or-null rule for absent scalars, routes an
     * absent collection-valued parameter through {@link #absentCollectionValue}, coerces the
     * present value to the declared scalar type, and runs the input processor for String values when
     * a route chain is active.
     *
     * <p>A parameter whose {@code componentType()} is non-{@code null} is <em>always</em> handled by one
     * of the two collection branches — {@link #absentCollectionValue} when the request supplied nothing,
     * {@link #coerceCollection} otherwise — so the scalar coercion below can never be reached for a
     * collection-declared parameter, whatever shape the binder produced for it.
     *
     * @param paramMeta the parameter metadata describing the source and declared type
     * @param policies  the effective input policies for this parameter
     * @param rv        the request value to extract from (never {@code null}; wraps {@code null} when
     *                  the request supplied no value)
     * @return the extracted parameter value, or {@code null} if absent and no default applies
     */
    private Object extractScalarValue(
            ResourceMethodMeta.ParamMeta paramMeta, EffectiveInputPolicies policies, RequestValue rv) {
        if (rv == null || rv.isNull()) {
            // A collection-valued parameter has its own absence contract (ADR-0191 / Jakarta REST 4.0):
            // it must never ask for a converter targeting the *collection* type, which is what
            // coerceString would do (there is none, so it would 500).
            if (paramMeta.componentType() != null) {
                return absentCollectionValue(paramMeta);
            }
            if (paramMeta.defaultValue() != null) {
                return coerceString(paramMeta.defaultValue(), paramMeta);
            }
            return null;
        }

        // Multi-valued parameter (e.g. @QueryParam("ids") List<Integer> / Set / array): the
        // BoundRequest binds repeated values as a JsonArray of raw strings, so coerce each element to
        // the declared component type and materialise the collection. Without this the raw JsonArray
        // would reach the resource method and fail the invocation.
        //
        // The gate is componentType() alone, NOT "componentType() && is a JsonArray": a collection-valued
        // parameter must never fall through to the scalar branch below, which would ask the resolver for
        // a converter targeting the *collection* type (there is none, so it 500s — the same failure mode
        // the absence branch above exists to prevent). Any non-JsonArray bound value is therefore treated
        // as the collection's single element, so a binder that hands over a scalar shape — e.g. because a
        // descriptor lookup missed and left the value scalar-wrapped — degrades to a one-element
        // collection instead of failing the request.
        if (paramMeta.componentType() != null) {
            Object bound = rv.get();
            List<?> rawValues = bound instanceof io.vertx.core.json.JsonArray jsonArray
                    ? jsonArray.getList()
                    : Collections.singletonList(bound);
            return coerceCollection(rawValues, paramMeta, policies);
        }

        Object value = coerce(rv, paramMeta);

        if (value instanceof String s && objectProcessor != null && !policies.hasNoRouteChains()) {
            value = objectProcessor.processStructuredBody(
                    s, String.class, policies, toInputLocation(paramMeta.source()));
        }

        return value;
    }

    /**
     * Coerces a multi-value parameter's raw request values into the declared collection type for a
     * {@code List<T>}/{@code Set<T>}/{@code SortedSet<T>}/{@code NavigableSet<T>}/
     * {@code Collection<T>}/{@code T[]} parameter, coercing each element to the parameter's component
     * type via the {@link ParamConversionResolver}. Element conversion is <em>fail-closed</em>: a
     * malformed element propagates the resolver's
     * {@link dev.vertique.rest.core.convert.ParamConversionException} (mapped to 400) rather than
     * silently retaining the raw string, so a single bad element fails the whole collection cleanly.
     *
     * <p>Every element also traverses the input-policy chain exactly as its own source's scalar value
     * does — same guard ({@code objectProcessor != null && !policies.hasNoRouteChains()}), same
     * {@link InputObjectProcessor#processStructuredBody} call, same {@link InputLocation} derived from
     * the parameter source, and the same position relative to conversion (before it for FORM, after it
     * for QUERY/HEADER/COOKIE — see {@link #convertElements}). Without this a
     * {@code @QueryParam List<String>} would bypass the canonicalization/sanitization chain that the
     * equivalent {@code @QueryParam String} traverses.
     *
     * <p>Element conversion and policy processing are delegated to {@link #convertElements};
     * materialization (declared-type selection and the read-only guarantee) to
     * {@link #materializeCollection}.
     *
     * @param rawValues the parameter's raw request values, in the order the transport reported them:
     *                  the bound {@code JsonArray}'s elements for QUERY/HEADER/COOKIE (or a singleton
     *                  list holding the bound value when it is not a {@code JsonArray}), or
     *                  {@code formAttributes().getAll(name)} for FORM
     * @param paramMeta the parameter metadata supplying the collection type and component type
     * @param policies  the effective input policies applied to each {@link String} element
     * @return the materialised read-only {@link List}/{@link Set}/{@link SortedSet}/
     *     {@link NavigableSet}, or the materialised array, of coerced elements
     */
    private Object coerceCollection(
            List<?> rawValues, ResourceMethodMeta.ParamMeta paramMeta, EffectiveInputPolicies policies) {
        return materializeCollection(
                convertElements(rawValues, paramMeta, policies), paramMeta.type(), paramMeta.componentType());
    }

    /**
     * Converts every raw request value of a collection-valued parameter to its declared component
     * type and runs each converted {@link String} element through the input-policy chain.
     *
     * <p>Shared by every multi-value source: both the QUERY/HEADER/COOKIE path (whose values arrive as
     * a bound {@code JsonArray}) and the FORM path (whose values arrive as
     * {@code formAttributes().getAll(name)}) reach it through {@link #coerceCollection}. One
     * implementation keeps the sources from diverging in either conversion or policy semantics.
     *
     * <p>Conversion is <em>fail-closed</em> per element: a malformed element propagates the
     * resolver's {@link dev.vertique.rest.core.convert.ParamConversionException} (mapped to 400)
     * rather than silently retaining the raw string. Policy processing uses the same guard the scalar
     * path uses ({@code objectProcessor != null && !policies.hasNoRouteChains()}) and the
     * {@link InputLocation} derived from the parameter source.
     *
     * <p><strong>The chain's position relative to conversion is per source</strong>, because each
     * source's own scalar rule differs and an element must traverse exactly the chain its scalar
     * equivalent traverses:
     * <ul>
     *   <li><b>FORM</b> — the <em>raw</em> form string is processed <em>before</em> conversion, and the
     *       post-conversion pass is skipped. This mirrors {@link #extractFormParam}'s scalar text-field
     *       branch, which processes {@code getFormAttribute(name)} and only then calls
     *       {@code coerceString}. Without it a canonicalizer that <em>normalizes</em> a value (say,
     *       stripping whitespace) would fix {@code @FormParam Integer} but not
     *       {@code @FormParam List<Integer>}: the raw {@code " 5"} would reach the {@code Integer}
     *       converter and 400 while the scalar succeeded — an asymmetry inside one source.</li>
     *   <li><b>QUERY / HEADER / COOKIE</b> — the <em>converted</em> element is processed, and only when
     *       it is still a {@link String}. This mirrors {@link #extractScalarValue}, which coerces the
     *       bound value first and processes only a {@code String} result.</li>
     * </ul>
     *
     * <p>Defaults are not processed on either path, mirroring the scalar rule (see
     * {@link #absentCollectionValue}).
     *
     * @param rawValues the raw request values, in whatever order the transport reported them (F8 —
     *                  ordering is not a framework guarantee); {@code null} entries are preserved
     * @param paramMeta the collection parameter metadata (its {@code componentType()} is non-{@code null})
     * @param policies  the effective input policies applied to each {@link String} element
     * @return the converted, policy-processed elements, in the order given
     */
    private List<Object> convertElements(
            List<?> rawValues, ResourceMethodMeta.ParamMeta paramMeta, EffectiveInputPolicies policies) {
        ConversionContext elementContext = componentContext(paramMeta, paramMeta.componentType());
        // Hoisted out of the loop: all of these are per-route constants.
        boolean processElements = objectProcessor != null && !policies.hasNoRouteChains();
        boolean processBeforeConversion = paramMeta.source() == ResourceMethodMeta.ParamSource.FORM;
        InputLocation location = processElements ? toInputLocation(paramMeta.source()) : null;
        List<Object> coerced = new ArrayList<>(rawValues.size());
        for (Object raw : rawValues) {
            if (raw == null) {
                coerced.add(null);
                continue;
            }
            String rawValue = raw.toString();
            if (processElements && processBeforeConversion) {
                // Same cast as the FORM scalar branch: a String target must yield a String.
                rawValue = (String) objectProcessor.processStructuredBody(rawValue, String.class, policies, location);
            }
            Object element = paramConversionResolver.fromString(rawValue, elementContext);
            if (processElements && !processBeforeConversion && element instanceof String s) {
                element = objectProcessor.processStructuredBody(s, String.class, policies, location);
            }
            coerced.add(element);
        }
        return coerced;
    }

    /**
     * Applies the absence contract for a collection-valued parameter — a parameter whose
     * {@code componentType()} is non-{@code null} — when the request supplied no value for its name.
     *
     * <p>Per Jakarta REST 4.0 (and ADR-0191):
     * <ul>
     *   <li>with a {@code @DefaultValue}, the result is a <em>single-entry</em> collection holding the
     *       default converted through the same per-element context {@link #coerceCollection} uses (the
     *       declared collection type has no converter of its own, so the element context is the only
     *       correct one);</li>
     *   <li>without a {@code @DefaultValue}, the result is an <em>empty</em> collection for
     *       {@link List}/{@link Set}/{@link SortedSet}/{@link NavigableSet}/{@link Collection}, and
     *       {@code null} for an array — an array is not one of the collection interfaces the spec
     *       names, so {@code @DefaultValue}'s "{@code null} for other object types" rule applies.</li>
     * </ul>
     *
     * <p>A {@code @DefaultValue} on an array is not covered by the spec; the framework materialises a
     * single-element array by analogy with the single-entry collection rule (ADR-0191). Defaults are
     * <em>not</em> submitted to the input-policy chain, mirroring the scalar rule in
     * {@link #extractScalarValue}.
     *
     * @param paramMeta the collection-valued parameter metadata (its {@code componentType()} is
     *                  non-{@code null})
     * @return the read-only empty or single-entry collection, the single-element array, or
     *     {@code null} for an absent array with no default
     */
    private Object absentCollectionValue(ResourceMethodMeta.ParamMeta paramMeta) {
        Class<?> declaredType = paramMeta.type();
        Class<?> componentType = paramMeta.componentType();
        String defaultValue = paramMeta.defaultValue();
        if (defaultValue == null) {
            return declaredType.isArray() ? null : materializeCollection(List.of(), declaredType, componentType);
        }
        Object element = paramConversionResolver.fromString(defaultValue, componentContext(paramMeta, componentType));
        return materializeCollection(Collections.singletonList(element), declaredType, componentType);
    }

    /**
     * Materialises already-converted elements into the declared collection or array type, returning a
     * <em>read-only</em> collection as Jakarta REST 4.0 requires ("the resulting collection is
     * read-only").
     *
     * <p>The cascade order is load-bearing and matches the declared shapes
     * {@code ResourceScanner.isSupportedCollectionRawType} accepts:
     * <ol>
     *   <li>an array materialises an array — no read-only wrapper exists for arrays, so an injected
     *       array is mutable by construction (a deliberate, documented asymmetry);</li>
     *   <li>{@link NavigableSet} is tested <em>before</em> {@link SortedSet}, because
     *       {@link Collections#unmodifiableSortedSet} returns a {@code SortedSet} that is NOT
     *       assignable to a {@code NavigableSet}-declared parameter and would make reflective
     *       {@code Method.invoke} throw {@code IllegalArgumentException} → 500;</li>
     *   <li>{@link SortedSet} materialises a wrapped {@link TreeSet};</li>
     *   <li>any other {@link Set} materialises a wrapped {@link LinkedHashSet};</li>
     *   <li>{@link List} and {@link Collection} materialise a wrapped {@link ArrayList}.</li>
     * </ol>
     *
     * <p>The {@code TreeSet} shapes require {@link Comparable} elements, and that is <em>not</em>
     * implied by the element type alone. It holds for array shapes, whose component type
     * {@code ResourceScanner.isScalarArrayComponent} restricts to {@link String}, a boxed numeric,
     * {@link Boolean}, {@link Character}, or an enum — all {@code Comparable}. The parameterized
     * collection shapes are unrestricted: {@code ResourceScanner.isSupportedCollectionRawType} gates
     * only the raw type, and the type-argument read accepts <em>any</em> concrete class as the element
     * type, including a non-{@code Comparable} one. What makes the {@code TreeSet} branches safe is the
     * startup guard: a {@code SortedSet}/{@code NavigableSet} parameter whose element type is not
     * comparable to <em>itself</em> — it does not implement {@code Comparable}, or implements it against
     * an unrelated type, whose {@code compareTo(Object)} bridge would cast and throw — is rejected at
     * registration with
     * {@link RouteRegistrationViolation.ViolationType#NON_COMPARABLE_SORTED_SET_ELEMENT}
     * ({@code RouteValidator.addSortedSetElementViolations}), so no such parameter ever reaches this
     * method.
     *
     * <p>Element ordering is whatever the underlying transport reported (Vert.x documents no ordering
     * for repeated parameters), except for the sorted shapes; it is explicitly not a framework
     * guarantee.
     *
     * @param elements      the already-converted, already-policy-processed elements
     * @param declaredType  the declared parameter type (a supported collection interface or an array)
     * @param componentType the element type, used as the array component type
     * @return the read-only collection, or the array, assignable to {@code declaredType}
     */
    private static Object materializeCollection(List<Object> elements, Class<?> declaredType, Class<?> componentType) {
        if (declaredType.isArray()) {
            Object array = Array.newInstance(componentType, elements.size());
            for (int i = 0; i < elements.size(); i++) {
                Array.set(array, i, elements.get(i));
            }
            return array;
        }
        // NavigableSet MUST precede SortedSet: unmodifiableSortedSet returns a plain SortedSet.
        if (NavigableSet.class.isAssignableFrom(declaredType)) {
            return Collections.unmodifiableNavigableSet(new TreeSet<>(elements));
        }
        if (SortedSet.class.isAssignableFrom(declaredType)) {
            return Collections.unmodifiableSortedSet(new TreeSet<>(elements));
        }
        if (Set.class.isAssignableFrom(declaredType)) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(elements));
        }
        return Collections.unmodifiableList(new ArrayList<>(elements));
    }

    /**
     * Looks up the {@link RequestValue} for the given parameter in a bound-request parameter map.
     * Header and cookie maps are keyed case-insensitively (lower-cased) by {@link BoundRequest}, so
     * the declared parameter name is normalized before the lookup for those sources.
     *
     * @param paramMap  the bound parameter map (path / query / header / cookie)
     * @param paramMeta the parameter metadata supplying the name and source
     * @return the matching {@link RequestValue}, or {@code null} when the key is absent
     */
    private static RequestValue lookup(Map<String, RequestValue> paramMap, ResourceMethodMeta.ParamMeta paramMeta) {
        String key =
                switch (paramMeta.source()) {
                    case HEADER, COOKIE -> paramMeta.name().toLowerCase(java.util.Locale.ROOT);
                    default -> paramMeta.name();
                };
        return paramMap.get(key);
    }

    /**
     * Converts a bound {@link RequestValue} to the declared parameter type, routing string-shaped
     * values through the {@link ParamConversionResolver}.
     *
     * <p>{@link String} and {@link JsonObject} targets keep their identity fast-paths. For every other
     * target: a declared scalar is already coerced to its type at bind time (the binding facade ran the
     * same resolver), so a non-{@link String} bound value is returned as-is; a value still shaped as a
     * raw {@link String} — a declared scalar whose lenient bind retained the raw string, or an
     * UNDECLARED {@code @BeanParam} field bound as its raw string — is converted here through the
     * resolver, failing closed (a 400 {@code ParamConversionException}) on a malformed value.
     *
     * @param rv        the request value to convert
     * @param paramMeta the parameter metadata supplying the declared type and conversion context
     * @return the converted value
     */
    private Object coerce(RequestValue rv, ResourceMethodMeta.ParamMeta paramMeta) {
        Class<?> targetType = paramMeta.type();
        if (targetType == String.class) return rv.getString();
        if (targetType == JsonObject.class) return rv.getJsonObject();

        Object raw = rv.get();
        if (raw instanceof String s) {
            return paramConversionResolver.fromString(s, scalarContext(paramMeta));
        }
        return raw;
    }

    /**
     * Coerces a raw string value to the declared parameter type via the {@link ParamConversionResolver},
     * so the reflective dispatch path, the binding facade, and the outbound client share one conversion
     * chain. Used for {@code @DefaultValue} and form-field values.
     *
     * @param value     the string value to convert
     * @param paramMeta the parameter metadata supplying the declared type and conversion context
     * @return the converted value
     */
    private Object coerceString(String value, ResourceMethodMeta.ParamMeta paramMeta) {
        return paramConversionResolver.fromString(value, scalarContext(paramMeta));
    }

    /**
     * Returns the route-scoped scalar {@link ConversionContext} for {@code paramMeta}, computing and
     * caching it on first use so the per-request conversion path allocates no fresh context or
     * annotation array.
     *
     * @param paramMeta the parameter metadata
     * @return the cached scalar conversion context for this parameter
     */
    private ConversionContext scalarContext(ResourceMethodMeta.ParamMeta paramMeta) {
        return scalarContextCache.computeIfAbsent(paramMeta, ConversionContexts::forParamMeta);
    }

    /**
     * Returns the route-scoped per-element {@link ConversionContext} for the collection-valued
     * {@code paramMeta}, computing and caching it on first use.
     *
     * @param paramMeta     the collection parameter metadata
     * @param componentType the element type to convert each value to
     * @return the cached per-element conversion context for this parameter
     */
    private ConversionContext componentContext(ResourceMethodMeta.ParamMeta paramMeta, Class<?> componentType) {
        return componentContextCache.computeIfAbsent(
                paramMeta, pm -> ConversionContexts.forComponent(pm, componentType));
    }

    // --- Body deserialization ---

    /**
     * Deserializes the request body to the target type by delegating to the first matching
     * {@link RequestBodyDecoder} from the priority-sorted decoder list. Policies are derived from
     * {@code pm.annotations()} at call time.
     *
     * @param body        the request body value
     * @param targetType  the desired Java type (raw class)
     * @param genericType the full generic type (e.g. {@code List<MyPojo>}), or {@code null}
     * @param ctx         the current routing context (for Content-Type inspection)
     * @param pm          the parameter metadata carrying parameter-level input policy annotations
     * @return the deserialized body value, or {@code null} if the body is absent
     * @throws jakarta.ws.rs.NotSupportedException if no decoder can handle the content type
     */
    private Object deserializeBody(
            RequestValue body,
            Class<?> targetType,
            java.lang.reflect.Type genericType,
            RoutingContext ctx,
            ResourceMethodMeta.ParamMeta pm) {
        return deserializeBody(body, targetType, genericType, ctx, resolveParamPolicies(pm));
    }

    /**
     * Deserializes the request body to the target type using a precomputed
     * {@link EffectiveInputPolicies} argument, bypassing per-call annotation re-resolution.
     *
     * <p>Logs a warning if a multipart body is encountered, since multipart content should
     * be handled via {@code @FormParam} or {@code List<EntityPart>} parameters instead.
     *
     * <p>When an {@link InputObjectProcessor} is active, structured bodies (JSON objects and
     * JSON arrays) are intercepted before materialization so that canonicalization and
     * sanitization chains can be applied.
     *
     * <p>This overload is used by {@link GeneratedJaxRsSupport} so that generated execution
     * plans can pass policies computed at codegen time.
     *
     * @param body        the request body value
     * @param targetType  the desired Java type (raw class)
     * @param genericType the full generic type (e.g. {@code List<MyPojo>}), or {@code null}
     * @param ctx         the current routing context (for Content-Type inspection)
     * @param policies    precomputed effective input policies for this parameter
     * @return the deserialized body value, or {@code null} if the body is absent
     * @throws jakarta.ws.rs.NotSupportedException if no decoder can handle the content type
     */
    Object deserializeBody(
            RequestValue body,
            Class<?> targetType,
            java.lang.reflect.Type genericType,
            RoutingContext ctx,
            EffectiveInputPolicies policies) {
        if (body == null || body.isNull()) return null;

        String contentType = ctx.request().getHeader("Content-Type");

        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            log.warn(
                    "Unexpected multipart body on operation '{}'; use @FormParam or List<EntityPart> instead of a body parameter",
                    meta.operationId());
        }

        // Two-phase processing for structured bodies when a processor is active
        if (objectProcessor != null && isStructuredBodyTarget(targetType)) {

            String lowerContentType = contentType != null ? contentType.toLowerCase() : "";

            // JSON body — intercept intermediate map before materialization
            if (lowerContentType.isEmpty() || lowerContentType.contains("json")) {
                // FR-JSON-024B/022/023: a non-vertx JSON profile resolved for this method (slice 2.1) is
                // stashed on the routing context under KEY_RESOLVED_BODY_MAPPER. When present it owns the
                // two-phase MATERIALIZATION of the processed body; when absent (the vertx default) the
                // calls below are byte-for-byte identical to today (JsonObject.mapTo / DatabindCodec).
                ObjectMapper profileMapper = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER);
                JsonObject jsonBody = body.getJsonObject();
                if (jsonBody != null && !Collection.class.isAssignableFrom(targetType) && !targetType.isArray()) {
                    Map<String, Object> intermediate = jsonBody.getMap();
                    Object processed = objectProcessor.processStructuredBody(
                            intermediate, targetType, policies, InputLocation.BODY);
                    if (processed instanceof Map<?, ?> processedMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typedMap = (Map<String, Object>) processedMap;
                        if (profileMapper != null) {
                            return ProfileBodyMaterialization.convertValue(profileMapper, typedMap, targetType);
                        }
                        return new JsonObject(typedMap).mapTo(targetType);
                    }
                }
                // JSON array — List<T>, Set<T>, T[] — intercept before decoder chain
                if (Collection.class.isAssignableFrom(targetType) || targetType.isArray()) {
                    io.vertx.core.json.JsonArray jsonArray = body.getJsonArray();
                    if (jsonArray != null) {
                        java.lang.reflect.Type resolvedType = genericType != null ? genericType : targetType;
                        Object processed = objectProcessor.processStructuredBody(
                                jsonArray.getList(), resolvedType, policies, InputLocation.BODY);
                        if (processed instanceof List<?> processedList) {
                            com.fasterxml.jackson.databind.JavaType javaType =
                                    DatabindCodec.mapper().getTypeFactory().constructType(resolvedType);
                            if (profileMapper != null) {
                                return ProfileBodyMaterialization.convertValue(profileMapper, processedList, javaType);
                            }
                            return DatabindCodec.mapper().convertValue(processedList, javaType);
                        }
                    }
                }
                // JSON string body — apply processing before decoder chain
                if (targetType == String.class) {
                    String stringBody = body.getString();
                    if (stringBody != null && !policies.hasNoRouteChains()) {
                        return objectProcessor.processStructuredBody(
                                stringBody, String.class, policies, InputLocation.BODY);
                    }
                    return stringBody;
                }
                // Fall through to decoder chain for unprocessed bodies
            } else if (lowerContentType.startsWith("application/x-www-form-urlencoded")) {
                // Form-urlencoded body as POJO
                JsonObject json = new JsonObject();
                for (var entry : ctx.request().formAttributes()) {
                    json.put(entry.getKey(), entry.getValue());
                }
                if (!json.isEmpty()) {
                    Object processed = objectProcessor.processStructuredBody(
                            json.getMap(), targetType, policies, InputLocation.BODY);
                    if (processed instanceof Map<?, ?> processedMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typedMap = (Map<String, Object>) processedMap;
                        return new JsonObject(typedMap).mapTo(targetType);
                    }
                }
                return null;
            } else if (targetType == String.class) {
                // String body — apply route-level processing
                String stringBody = body.getString();
                if (stringBody != null && !policies.hasNoRouteChains()) {
                    return objectProcessor.processStructuredBody(
                            stringBody, String.class, policies, InputLocation.BODY);
                }
                return stringBody;
            }
        }

        for (RequestBodyDecoder decoder : decoders) {
            if (decoder.canDecode(targetType, contentType)) {
                return decoder.decode(ctx, body, targetType, genericType);
            }
        }

        // No decoder found — fail with 415 so the error pipeline produces a proper response
        throw new jakarta.ws.rs.NotSupportedException("No RequestBodyDecoder for Content-Type: " + contentType);
    }

    /**
     * Returns {@code true} for body target types that support two-phase intermediate processing.
     * Excludes raw binary types, Vert.x JSON wrappers, collections, and arrays — these bypass
     * the intermediate map path and go directly to the decoder chain.
     *
     * @param targetType the raw target class
     * @return {@code true} when the target type supports intermediate map processing
     */
    private static boolean isStructuredBodyTarget(Class<?> targetType) {
        return targetType != byte[].class
                && targetType != io.vertx.core.buffer.Buffer.class
                && targetType != JsonObject.class
                && targetType != io.vertx.core.json.JsonArray.class;
    }

    // --- Form parameter extraction ---

    /**
     * Extracts a form parameter from the routing context. Policies are derived from
     * {@code pm.annotations()} at call time.
     *
     * @param pm  the parameter metadata
     * @param ctx the current routing context
     * @return the extracted value, or {@code null} if not found
     */
    private Object extractFormParam(ResourceMethodMeta.ParamMeta pm, RoutingContext ctx) {
        return extractFormParam(pm, resolveParamPolicies(pm), ctx);
    }

    /**
     * Extracts a form parameter from the routing context using a precomputed
     * {@link EffectiveInputPolicies} argument, bypassing per-call annotation re-resolution.
     *
     * <p>Dispatches by target type, in this order:
     * <ol>
     *   <li>a scalar native multipart target — {@link FileUpload} or {@link EntityPart};</li>
     *   <li>a native multipart <em>collection</em> target — {@code List<FileUpload>} or
     *       {@code List<EntityPart>}. These guards are keyed on the native component type
     *       <em>together with</em> {@code type() == List.class}: {@link List} is the only collection
     *       shape with a native materialization (ADR-0191 decision 6), and any other collection shape
     *       carrying a native component type is rejected at startup by {@link RouteValidator} rather
     *       than silently falling through to string conversion here;</li>
     *   <li>a text collection target — any {@code componentType() != null} shape
     *       ({@code List}/{@code Set}/{@code SortedSet}/{@code NavigableSet}/{@code Collection}/
     *       {@code T[]}), bound from <em>all</em> submitted values for the field;</li>
     *   <li>a scalar text form field (String / primitives / any convertible type).</li>
     * </ol>
     *
     * <p>This overload is used by {@link GeneratedJaxRsSupport} so that generated execution
     * plans can pass policies computed at codegen time.
     *
     * @param pm       the parameter metadata
     * @param policies precomputed effective input policies for this parameter
     * @param ctx      the current routing context
     * @return the extracted value, or {@code null} if not found
     */
    Object extractFormParam(ResourceMethodMeta.ParamMeta pm, EffectiveInputPolicies policies, RoutingContext ctx) {
        if (pm.type() == FileUpload.class) {
            return ctx.fileUploads().stream()
                    .filter(fu -> fu.name().equals(pm.name()))
                    .findFirst()
                    .orElse(null);
        }
        if (pm.type() == EntityPart.class) {
            FileUpload fu = ctx.fileUploads().stream()
                    .filter(f -> f.name().equals(pm.name()))
                    .findFirst()
                    .orElse(null);
            if (fu != null) {
                return new VertxFileUploadEntityPart(ctx.vertx(), fu);
            }
            String formValue = ctx.request().getFormAttribute(pm.name());
            if (formValue != null) {
                return new FormFieldEntityPart(pm.name(), formValue);
            }
            return null;
        }
        // Native multipart collections: List-only by contract, and read-only like every other
        // injected collection (ADR-0191 decision 3).
        if (pm.componentType() == FileUpload.class && pm.type() == List.class) {
            // Stream.toList() is already unmodifiable.
            return ctx.fileUploads().stream()
                    .filter(fu -> fu.name().equals(pm.name()))
                    .toList();
        }
        if (pm.componentType() == EntityPart.class && pm.type() == List.class) {
            List<EntityPart> parts = new ArrayList<>();
            // File uploads first
            ctx.fileUploads().stream()
                    .filter(fu -> fu.name().equals(pm.name()))
                    .map(fu -> new VertxFileUploadEntityPart(ctx.vertx(), fu))
                    .forEach(parts::add);
            // Then text form fields with the same name
            for (Map.Entry<String, String> entry : ctx.request().formAttributes()) {
                if (entry.getKey().equals(pm.name())) {
                    parts.add(new FormFieldEntityPart(entry.getKey(), entry.getValue()));
                }
            }
            return Collections.unmodifiableList(parts);
        }
        // Text collection form field: bind ALL submitted values for the name, so a repeated
        // x-www-form-urlencoded/multipart field materialises the declared collection shape exactly as
        // the equivalent @QueryParam would (ADR-0191 decision 2).
        if (pm.componentType() != null) {
            // getAll() returns an EMPTY list — never null — for an absent field, so emptiness MUST be
            // tested before materializing: otherwise the absence contract (empty collection, or a
            // single-entry collection for a @DefaultValue) would collapse into an empty collection and
            // silently swallow the default.
            List<String> values = ctx.request().formAttributes().getAll(pm.name());
            if (values.isEmpty()) {
                return absentCollectionValue(pm);
            }
            return coerceCollection(values, pm, policies);
        }
        // Scalar text form field
        String formValue = ctx.request().getFormAttribute(pm.name());
        if (formValue == null) {
            if (pm.defaultValue() != null) {
                return coerceString(pm.defaultValue(), pm);
            }
            return null;
        }
        if (objectProcessor != null && !policies.hasNoRouteChains()) {
            formValue = (String)
                    objectProcessor.processStructuredBody(formValue, String.class, policies, InputLocation.FORM);
        }
        return coerceString(formValue, pm);
    }

    /**
     * Extracts all multipart parts as a list of {@link EntityPart} instances.
     * File uploads are wrapped as {@link VertxFileUploadEntityPart}, form attributes
     * as {@link FormFieldEntityPart}.
     *
     * <p>Promoted to package-private so that {@link GeneratedJaxRsSupport} can expose it to
     * generated execution plans without duplicating the logic.
     *
     * <p>The returned list is <em>read-only</em>: an aggregate {@code List<EntityPart>} is a
     * {@code @FormParam} collection injection target like any other, so it carries the same
     * read-only guarantee (ADR-0191 decision 3).
     *
     * @param ctx the current routing context
     * @return an unmodifiable list of all entity parts
     */
    List<EntityPart> extractAllEntityParts(RoutingContext ctx) {
        List<EntityPart> parts = new ArrayList<>();
        Set<String> fileUploadNames = new HashSet<>();
        for (FileUpload fu : ctx.fileUploads()) {
            parts.add(new VertxFileUploadEntityPart(ctx.vertx(), fu));
            fileUploadNames.add(fu.name());
        }
        // Add form attributes that don't duplicate a file upload name
        for (Map.Entry<String, String> entry : ctx.request().formAttributes()) {
            if (!fileUploadNames.contains(entry.getKey())) {
                parts.add(new FormFieldEntityPart(entry.getKey(), entry.getValue()));
            }
        }
        return Collections.unmodifiableList(parts);
    }

    // --- Bean param extraction ---

    /**
     * Extracts a {@code @BeanParam} or {@code @RequestParams} composite parameter by populating
     * its components via Jackson's {@code ObjectMapper.convertValue()}.
     *
     * <p>Field metadata is resolved once per bean type via {@link #BEAN_PARAM_CACHE} and reused
     * on every subsequent request, eliminating per-request reflection overhead.
     *
     * @param beanType     the record or POJO class to instantiate
     * @param boundRequest the bound request parameters
     * @param ctx          the routing context (for form params)
     * @return the populated instance
     */
    private Object extractBeanParam(Class<?> beanType, BoundRequest boundRequest, RoutingContext ctx) {
        List<BeanFieldEntry> fields = BEAN_PARAM_CACHE.computeIfAbsent(beanType, ParameterExtractor::computeBeanFields);
        Map<String, Object> values = new LinkedHashMap<>();
        for (BeanFieldEntry entry : fields) {
            Object value = extractParamValue(entry.meta(), boundRequest, ctx);
            if (value != null) {
                values.put(entry.name(), value);
            }
        }

        // Apply input processing to the intermediate map before materialization
        if (objectProcessor != null) {
            EffectiveInputPolicies policies =
                    new EffectiveInputPolicies(meta.routeCanonicalizerChain(), meta.routeSanitizerChain());
            Object processed =
                    objectProcessor.processStructuredBody(values, beanType, policies, InputLocation.BEAN_PARAM);
            if (processed instanceof Map<?, ?> processedMap) {
                values = new LinkedHashMap<>();
                for (var entry2 : processedMap.entrySet()) {
                    values.put(entry2.getKey().toString(), entry2.getValue());
                }
            }
        }

        return DatabindCodec.mapper().convertValue(values, beanType);
    }

    /**
     * Materializes a bean-param object from an explicit ordered field list, bypassing the
     * {@link #BEAN_PARAM_CACHE} reflective walk. Per-field
     * {@link EffectiveInputPolicies} are derived internally from each field's
     * {@link ResourceMethodMeta.ParamMeta#annotations()} using the supplied route-level baseline,
     * applying the same algorithm as {@link #resolveParamPolicies(ResourceMethodMeta.ParamMeta,
     * List, List)}. This ensures field-level input-policy annotations ({@code @Canonicalize},
     * {@code @Sanitize}, {@code @SkipCanonicalization}, {@code @SkipSanitization}) are honoured on
     * the generated-companion path, maintaining parity with the reflective path.
     *
     * <p>The overall processing contract is identical to {@link #extractBeanParam}: per-field
     * extraction happens first, then the assembled intermediate {@link LinkedHashMap} is submitted
     * to the {@link dev.vertique.rest.core.request.InputObjectProcessor} with {@code routePolicies}
     * before final Jackson conversion.
     *
     * @param fields           ordered array of bean field metadata; must not be {@code null};
     *                         each {@code meta().annotations()} should carry the field's declared
     *                         annotations so per-field policies can be derived
     * @param routePolicies    route-level policies used as the baseline for per-field policy
     *                         derivation and for the final intermediate-map processing step;
     *                         pass {@link EffectiveInputPolicies#NONE} to use empty chains
     * @param boundRequest     the bound request exposing parameter values as {@link RequestValue}s
     * @param ctx              the routing context (for form params)
     * @param beanType         the bean class to materialise via Jackson
     * @return the populated bean instance
     */
    Object materializeBean(
            BeanParamFieldMeta[] fields,
            EffectiveInputPolicies routePolicies,
            BoundRequest boundRequest,
            RoutingContext ctx,
            Class<?> beanType) {
        EffectiveInputPolicies[] perFieldPolicies = beanFieldPoliciesCache.computeIfAbsent(beanType, t -> {
            List<Class<? extends Canonicalizer>> routeCanon = routePolicies.routeCanonicalizers();
            List<Class<? extends Sanitizer>> routeSanit = routePolicies.routeSanitizers();
            EffectiveInputPolicies[] arr = new EffectiveInputPolicies[fields.length];
            for (int i = 0; i < fields.length; i++) {
                arr[i] = resolveParamPolicies(fields[i].meta(), routeCanon, routeSanit);
            }
            return arr;
        });

        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i++) {
            BeanParamFieldMeta fieldMeta = fields[i];
            EffectiveInputPolicies fieldPolicies = perFieldPolicies[i];
            Object value;
            if (fieldMeta.meta().source() == ResourceMethodMeta.ParamSource.FORM) {
                value = extractFormParam(fieldMeta.meta(), fieldPolicies, ctx);
            } else {
                value = extractScalarParam(fieldMeta.meta(), fieldPolicies, boundRequest);
            }
            if (value != null) {
                values.put(fieldMeta.name(), value);
            }
        }

        // Apply route-level input processing to the intermediate map before materialization
        if (objectProcessor != null && !routePolicies.hasNoRouteChains()) {
            Object processed =
                    objectProcessor.processStructuredBody(values, beanType, routePolicies, InputLocation.BEAN_PARAM);
            if (processed instanceof Map<?, ?> processedMap) {
                values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : processedMap.entrySet()) {
                    // Null values are intentional (absent fields); Jackson's convertValue handles them.
                    values.put(String.valueOf(entry.getKey()), (Object) entry.getValue());
                }
            }
        }

        return DatabindCodec.mapper().convertValue(values, beanType);
    }

    /**
     * Computes the ordered list of {@link BeanFieldEntry} instances for the given bean type.
     * Called at most once per type via {@link #BEAN_PARAM_CACHE}.
     *
     * <p><b>Bean-param model fast-path:</b> before the reflective walk, this method consults
     * {@link GeneratedJaxRsBeanParamRegistry#shared()} for a generated companion. On a hit, the
     * companion's {@link GeneratedJaxRsBeanParamModel#fields()} list is converted to
     * {@link BeanFieldEntry} instances (the records have parallel structure) and returned
     * directly, bypassing the reflective field walk. The converted list is cached in
     * {@link #BEAN_PARAM_CACHE} via the surrounding {@link Map#computeIfAbsent} call in
     * {@link #extractBeanParam}. A broken companion (registry throws) propagates immediately
     * and is NOT masked by a reflective fallback.
     *
     * <p><b>Field-hiding semantics (closed adjacent defect ex-#30):</b> the class hierarchy is
     * walked subclass-to-superclass and entries are deduplicated by field name on first encounter,
     * so a subclass field shadows a superclass field of the same name (the JLS-conformant
     * interpretation that {@code JaxRsBeanScanner} also uses at compile time). Without this dedup,
     * {@link #extractBeanParam}'s {@code LinkedHashMap}-based materialization would overwrite the
     * subclass entry with the superclass entry (last-write-wins) and silently switch behaviour to
     * superclass-wins. The two paths are now aligned.
     *
     * @param beanType the record or POJO class to inspect
     * @return ordered list of field-name/meta pairs for all JAX-RS-annotated components
     */
    private static List<BeanFieldEntry> computeBeanFields(Class<?> beanType) {
        // --- Bean-param model fast-path ---
        // A broken companion (registry throws) intentionally propagates — do NOT catch.
        java.util.Optional<GeneratedJaxRsBeanParamModel<?>> modelOpt =
                GeneratedJaxRsBeanParamRegistry.shared().lookup(beanType);
        if (modelOpt.isPresent()) {
            List<BeanParamFieldMeta> fields = modelOpt.get().fields();
            List<BeanFieldEntry> converted = new ArrayList<>(fields.size());
            for (BeanParamFieldMeta f : fields) {
                converted.add(new BeanFieldEntry(f.name(), f.meta()));
            }
            return Collections.unmodifiableList(converted);
        }

        List<BeanFieldEntry> entries = new ArrayList<>();
        if (beanType.isRecord()) {
            for (RecordComponent component : beanType.getRecordComponents()) {
                ResourceMethodMeta.ParamMeta meta = resolveComponentParam(component);
                if (meta != null) {
                    entries.add(new BeanFieldEntry(component.getName(), meta));
                }
            }
        } else {
            Set<String> seen = new HashSet<>();
            Class<?> current = beanType;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (!seen.add(field.getName())) {
                        // Subclass already declared a field with this name — subclass wins.
                        continue;
                    }
                    ResourceMethodMeta.ParamMeta fieldMeta = resolveFieldParam(field);
                    if (fieldMeta != null) {
                        entries.add(new BeanFieldEntry(field.getName(), fieldMeta));
                    }
                }
                current = current.getSuperclass();
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns the ordered {@code @BeanParam}/{@code @RequestParams} field metadata for
     * {@code beanType}, exposed as the public {@link BeanParamFieldMeta} counterpart so that
     * {@link JaxRsRouteRegistrar}'s startup validation can walk the same fields the request-time
     * {@link #materializeBean}/{@link #extractBeanParam} paths convert through the
     * {@link ParamConversionResolver} — without exposing the private {@link BeanFieldEntry} type.
     * Resolution goes through the same {@link #BEAN_PARAM_CACHE} (generated-companion fast path or
     * the reflective field/record-component walk), so this method never diverges from the request
     * dispatch path.
     *
     * @param beanType the {@code @BeanParam} record or POJO class to inspect
     * @return the ordered, immutable list of field metadata; empty if the type declares no
     *     JAX-RS-annotated fields
     */
    static List<BeanParamFieldMeta> beanParamFields(Class<?> beanType) {
        List<BeanFieldEntry> entries =
                BEAN_PARAM_CACHE.computeIfAbsent(beanType, ParameterExtractor::computeBeanFields);
        List<BeanParamFieldMeta> converted = new ArrayList<>(entries.size());
        for (BeanFieldEntry entry : entries) {
            converted.add(new BeanParamFieldMeta(entry.name(), entry.meta()));
        }
        return Collections.unmodifiableList(converted);
    }

    /**
     * Extracts a single parameter value, dispatching to {@link #extractFormParam} for form
     * parameters and {@link #extractParam} for all other sources.
     *
     * @param meta         the parameter metadata describing the source and type
     * @param boundRequest the bound request parameters
     * @param ctx          the routing context (for form params)
     * @return the extracted value, or {@code null} if absent
     */
    private Object extractParamValue(ResourceMethodMeta.ParamMeta meta, BoundRequest boundRequest, RoutingContext ctx) {
        return switch (meta.source()) {
            case FORM -> extractFormParam(meta, ctx);
            default -> extractParam(meta, boundRequest);
        };
    }

    // --- Bean param reflection helpers ---

    /**
     * Resolves JAX-RS parameter annotations on a field for {@code @BeanParam} extraction.
     *
     * @param field the field to inspect
     * @return a {@link ResourceMethodMeta.ParamMeta} if a JAX-RS annotation is found, {@code null}
     *     otherwise
     */
    private static ResourceMethodMeta.ParamMeta resolveFieldParam(Field field) {
        String defaultValue = null;
        var dv = field.getAnnotation(jakarta.ws.rs.DefaultValue.class);
        if (dv != null) defaultValue = dv.value();

        Class<?> type = field.getType();
        Annotation[] annotations = field.getAnnotations();

        var qp = field.getAnnotation(jakarta.ws.rs.QueryParam.class);
        if (qp != null)
            return paramMeta(qp.value(), ResourceMethodMeta.ParamSource.QUERY, type, defaultValue, annotations);
        var pp = field.getAnnotation(jakarta.ws.rs.PathParam.class);
        if (pp != null)
            return paramMeta(pp.value(), ResourceMethodMeta.ParamSource.PATH, type, defaultValue, annotations);
        var hp = field.getAnnotation(jakarta.ws.rs.HeaderParam.class);
        if (hp != null)
            return paramMeta(hp.value(), ResourceMethodMeta.ParamSource.HEADER, type, defaultValue, annotations);
        var cp = field.getAnnotation(jakarta.ws.rs.CookieParam.class);
        if (cp != null)
            return paramMeta(cp.value(), ResourceMethodMeta.ParamSource.COOKIE, type, defaultValue, annotations);
        var fp = field.getAnnotation(jakarta.ws.rs.FormParam.class);
        if (fp != null)
            return paramMeta(fp.value(), ResourceMethodMeta.ParamSource.FORM, type, defaultValue, annotations);
        return null;
    }

    /**
     * Resolves JAX-RS parameter annotations on a record component for {@code @RequestParams}
     * extraction. Mirrors {@link #resolveFieldParam(Field)} but operates on
     * {@link RecordComponent}.
     *
     * <p>JAX-RS annotations ({@code @QueryParam}, {@code @PathParam}, etc.) declare
     * {@code @Target(FIELD, METHOD, PARAMETER)} — they do <em>not</em> include
     * {@code RECORD_COMPONENT}. The Java compiler therefore propagates them to the generated
     * accessor method rather than leaving them on the component itself. This method checks the
     * component first and falls back to its accessor so that both placement styles work correctly.
     *
     * @param component the record component to inspect
     * @return a {@link ResourceMethodMeta.ParamMeta} if a JAX-RS annotation is found, {@code null}
     *     otherwise
     */
    static ResourceMethodMeta.ParamMeta resolveComponentParam(RecordComponent component) {
        // JAX-RS annotations land on the accessor method due to @Target not including RECORD_COMPONENT.
        // Use the accessor as the primary lookup source; the component itself is checked first for
        // any future annotations that do include RECORD_COMPONENT.
        Method accessor = component.getAccessor();

        String defaultValue = null;
        var dv = getAnnotation(component, accessor, jakarta.ws.rs.DefaultValue.class);
        if (dv != null) defaultValue = dv.value();

        Class<?> type = component.getType();
        Annotation[] annotations = accessor.getAnnotations();

        var qp = getAnnotation(component, accessor, jakarta.ws.rs.QueryParam.class);
        if (qp != null)
            return paramMeta(qp.value(), ResourceMethodMeta.ParamSource.QUERY, type, defaultValue, annotations);
        var pp = getAnnotation(component, accessor, jakarta.ws.rs.PathParam.class);
        if (pp != null)
            return paramMeta(pp.value(), ResourceMethodMeta.ParamSource.PATH, type, defaultValue, annotations);
        var hp = getAnnotation(component, accessor, jakarta.ws.rs.HeaderParam.class);
        if (hp != null)
            return paramMeta(hp.value(), ResourceMethodMeta.ParamSource.HEADER, type, defaultValue, annotations);
        var cp = getAnnotation(component, accessor, jakarta.ws.rs.CookieParam.class);
        if (cp != null)
            return paramMeta(cp.value(), ResourceMethodMeta.ParamSource.COOKIE, type, defaultValue, annotations);
        var fp = getAnnotation(component, accessor, jakarta.ws.rs.FormParam.class);
        if (fp != null)
            return paramMeta(fp.value(), ResourceMethodMeta.ParamSource.FORM, type, defaultValue, annotations);
        return null;
    }

    /**
     * Constructs a {@link ResourceMethodMeta.ParamMeta} with no converter and no generic type,
     * which is the common case for bean param field and record component resolution.
     *
     * @param name         the parameter name (from the JAX-RS annotation value)
     * @param source       the parameter source (QUERY, PATH, HEADER, etc.)
     * @param type         the Java type of the parameter
     * @param defaultValue the default value string, or {@code null}
     * @param annotations  the annotations on the element
     * @return a new {@link ResourceMethodMeta.ParamMeta}
     */
    private static ResourceMethodMeta.ParamMeta paramMeta(
            String name,
            ResourceMethodMeta.ParamSource source,
            Class<?> type,
            String defaultValue,
            Annotation[] annotations) {
        return new ResourceMethodMeta.ParamMeta(name, source, type, null, null, defaultValue, annotations);
    }

    /**
     * Looks up an annotation on a record component, falling back to its accessor method.
     * This is necessary because JAX-RS annotations declare {@code @Target(FIELD, METHOD, PARAMETER)}
     * and are therefore propagated to the accessor rather than being retained on the component.
     *
     * @param component      the record component to check first
     * @param accessor       the generated accessor method to fall back to
     * @param annotationType the annotation class to look for
     * @param <A>            the annotation type
     * @return the annotation if found on either the component or its accessor, {@code null} otherwise
     */
    private static <A extends Annotation> A getAnnotation(
            RecordComponent component, Method accessor, Class<A> annotationType) {
        A ann = component.getAnnotation(annotationType);
        return ann != null ? ann : accessor.getAnnotation(annotationType);
    }

    // --- Input processing helpers ---

    /**
     * Returns the effective input policies for the given parameter from the precomputed cache.
     * The cache is populated once at construction by walking each {@code ParamMeta.annotations()}
     * exactly once, eliminating per-request {@code AnnotationResolver.findMetaAnnotation} work
     * on the reflective fallback path.
     *
     * <p>Lookup is by reference identity over {@link ResourceMethodMeta#params()} (the same
     * {@link ResourceMethodMeta.ParamMeta} instance is reused for every request to a given route);
     * for parameters not in {@code meta.params()} (defensive fallback), policies are computed on
     * the fly from the route-level chains.
     *
     * @param pm the parameter metadata
     * @return the cached effective policies for this parameter
     */
    private EffectiveInputPolicies resolveParamPolicies(ResourceMethodMeta.ParamMeta pm) {
        List<ResourceMethodMeta.ParamMeta> params = meta.params();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) == pm) {
                return cachedParamPolicies[i];
            }
        }
        // Defensive: ParamMeta passed in isn't one of meta.params() — should not happen in
        // production. Fall back to on-the-fly resolution rather than throwing.
        return resolveParamPolicies(pm, meta.routeCanonicalizerChain(), meta.routeSanitizerChain());
    }

    /**
     * Maps a {@link ResourceMethodMeta.ParamSource} to the corresponding {@link InputLocation}
     * for use by the input processing engine.
     *
     * @param source the JAX-RS parameter source
     * @return the matching {@link InputLocation}
     */
    private static InputLocation toInputLocation(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH -> InputLocation.PATH;
            case QUERY -> InputLocation.QUERY;
            case HEADER -> InputLocation.HEADER;
            case COOKIE -> InputLocation.COOKIE;
            case FORM -> InputLocation.FORM;
            case BODY -> InputLocation.BODY;
            case BEAN_PARAM -> InputLocation.BEAN_PARAM;
            default -> InputLocation.BODY;
        };
    }
}
