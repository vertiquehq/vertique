// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.convert;

import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamSource;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;

/**
 * Builds {@link ConversionContext} instances for the JAX-RS inbound binding paths and exposes a
 * shared, built-ins-only {@link ParamConversionResolver} default.
 *
 * <p>The two factory methods map the jaxrs-internal parameter models —
 * {@link ResourceMethodMeta.ParamMeta} (the reflective dispatch model consumed by
 * {@code ParameterExtractor}) and {@link ParamDescriptor} (the binding-facade model consumed by
 * {@code DefaultBoundRequest}) — onto the framework-neutral {@link ConversionContext}. The
 * {@link ParamSource} is selected from the parameter's source/location; the lazy annotation supplier
 * is sourced from the {@link ResourceMethodMeta.ParamMeta}'s composed {@code annotationsLazy()} bridge
 * (and from the {@link ParamDescriptor}'s annotation list on the binding-facade path).
 *
 * <p>{@link #defaultResolver()} returns a resolver built from the framework built-ins only (no
 * application bindings, no JAX-RS providers). It is the fallback used by the binding paths when no
 * Dagger-managed resolver is supplied (e.g. test fixtures using the legacy 2-arg
 * {@code DefaultBoundRequest} constructor), preserving the pre-resolver scalar behavior while routing
 * every coercion through the single conversion chain.
 */
public final class ConversionContexts {

    /**
     * Shared resolver carrying only the framework built-in converters — no application bindings and
     * no JAX-RS providers. Immutable and stateless, so a single instance is safe to share.
     *
     * <p>This duplicates only the built-in baseline as a fallback for callers without a Dagger-managed
     * resolver; {@code RestModule} is the authoritative wiring of the production resolver (it folds in
     * application {@code ParamConverterBinding}s and JAX-RS providers).
     */
    private static final ParamConversionResolver DEFAULT_RESOLVER = ParamConversionResolver.builtins();

    private ConversionContexts() {}

    /**
     * Returns the shared built-ins-only resolver used when no Dagger-managed resolver is supplied.
     *
     * @return the default conversion resolver; never {@code null}
     */
    public static ParamConversionResolver defaultResolver() {
        return DEFAULT_RESOLVER;
    }

    /**
     * Builds a {@link ConversionContext} for a reflective-dispatch {@link ResourceMethodMeta.ParamMeta}.
     * The annotation supplier is the meta's composed {@code annotationsLazy()} bridge (an empty array
     * when none), so the resolver materializes the annotations only when a JAX-RS provider is present.
     *
     * @param pm the parameter metadata; must describe a conversion-applicable source
     *           (path/query/header/cookie/form)
     * @return the conversion context for {@code pm}
     */
    public static ConversionContext forParamMeta(ResourceMethodMeta.ParamMeta pm) {
        return new ConversionContext(
                pm.name(),
                toSource(pm.source()),
                pm.type(),
                pm.genericType(),
                pm.componentType(),
                pm.annotationsLazy());
    }

    /**
     * Builds a {@link ConversionContext} for a binding-facade {@link ParamDescriptor}.
     *
     * @param descriptor the declared-parameter descriptor; its {@link ParamDescriptor#location()}
     *                   must be a conversion-applicable location
     * @return the conversion context for {@code descriptor}
     */
    public static ConversionContext forDescriptor(ParamDescriptor descriptor) {
        return build(descriptor, descriptor.type(), descriptor.genericType(), descriptor.componentType());
    }

    /**
     * Builds a {@link ConversionContext} whose target type is the descriptor's <em>convertible</em>
     * type for startup validation: the element type for a collection-valued parameter (so a
     * {@code List<UUID>} validates against {@code UUID}), or the declared scalar type otherwise.
     *
     * <p>For a collection-valued descriptor this produces a <em>true element</em> context — identical
     * in shape to the runtime {@link #forComponent} path: {@code rawType = componentType},
     * {@code genericType = componentType}, {@code componentType = null}. Passing the descriptor's
     * collection {@code genericType} (e.g. {@code List<UUID>}) here would make a {@code genericType}-strict
     * {@link ParamConverterProvider} return {@code null} at startup (rejecting the route) while the same
     * element converts at request time — so the startup probe must mirror the runtime element shape.
     * For a scalar descriptor it keeps the declared scalar shape ({@code rawType = descriptor.type()},
     * {@code genericType = descriptor.genericType()}).
     *
     * @param descriptor the declared-parameter descriptor
     * @return the conversion context targeting the descriptor's convertible type
     */
    public static ConversionContext forDescriptorConvertibleType(ParamDescriptor descriptor) {
        Class<?> componentType = descriptor.componentType();
        if (componentType != null) {
            return build(descriptor, componentType, componentType, null);
        }
        return build(descriptor, descriptor.type(), descriptor.genericType(), descriptor.componentType());
    }

    /**
     * Builds a {@link ConversionContext} from a {@link ParamDescriptor} with explicit raw/generic/component
     * target types, sharing the descriptor's annotation array and source. The descriptor factories differ
     * only in which type triple they target (full type, scalar, or true collection element), so they
     * delegate here.
     *
     * @param descriptor    the declared-parameter descriptor
     * @param rawType       the raw target type to convert to (the declared type or its convertible element type)
     * @param genericType   the generic target type to expose to JAX-RS providers, or {@code null}
     * @param componentType the collection component type, or {@code null} for a scalar/element context
     * @return the conversion context targeting {@code rawType}
     */
    private static ConversionContext build(
            ParamDescriptor descriptor, Class<?> rawType, java.lang.reflect.Type genericType, Class<?> componentType) {
        Annotation[] annotations = descriptor.annotations().toArray(new Annotation[0]);
        return new ConversionContext(
                descriptor.name(),
                toSource(descriptor.location()),
                rawType,
                genericType,
                componentType,
                () -> annotations);
    }

    /**
     * Builds a {@link ConversionContext} for a single collection <em>element</em> of a declared
     * collection parameter, so the resolver converts each element to the component type while the
     * diagnostics still name the declaring parameter.
     *
     * @param pm            the collection parameter metadata
     * @param componentType the element type to convert to
     * @return the per-element conversion context
     */
    public static ConversionContext forComponent(ResourceMethodMeta.ParamMeta pm, Class<?> componentType) {
        // Caller invariant: pm.source() must be a conversion-applicable source (path/query/header/
        // cookie/form). toSource throws IllegalArgumentException otherwise — intentional, since a
        // non-convertible source has no per-element conversion context.
        return new ConversionContext(
                pm.name(), toSource(pm.source()), componentType, componentType, null, pm.annotationsLazy());
    }

    /**
     * Maps a jaxrs {@link ResourceMethodMeta.ParamSource} to the framework-neutral {@link ParamSource}.
     *
     * @param source the jaxrs parameter source; must be conversion-applicable
     * @return the matching {@link ParamSource}
     * @throws IllegalArgumentException if {@code source} is not a conversion-applicable source
     */
    public static ParamSource toSource(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH -> ParamSource.PATH;
            case QUERY -> ParamSource.QUERY;
            case HEADER -> ParamSource.HEADER;
            case COOKIE -> ParamSource.COOKIE;
            case FORM -> ParamSource.FORM;
            default -> throw new IllegalArgumentException("Not a conversion-applicable param source: " + source);
        };
    }

    /**
     * Maps a binding-facade {@link ParamLocation} to the framework-neutral {@link ParamSource}.
     *
     * @param location the parameter location
     * @return the matching {@link ParamSource}
     */
    public static ParamSource toSource(ParamLocation location) {
        return switch (location) {
            case PATH -> ParamSource.PATH;
            case QUERY -> ParamSource.QUERY;
            case HEADER -> ParamSource.HEADER;
            case COOKIE -> ParamSource.COOKIE;
            case FORM -> ParamSource.FORM;
        };
    }
}
