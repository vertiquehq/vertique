// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Full parameter-conversion chain shared by the JAX-RS inbound path and the REST-client outbound
 * path. It wraps the native, type-keyed {@link ParamConverterRegistry} and the JAX-RS
 * {@link ParamConverterProvider} SPI bridge, applying the framework error policy on top.
 *
 * <p>Resolution order for a given {@link ConversionContext}: native registry first, then — only when
 * the provider set is non-empty — the JAX-RS providers (consulted in {@link Priority}-ascending order,
 * the first provider returning a non-{@code null} converter wins; a provider with no {@code @Priority}
 * sorts last via {@link Integer#MAX_VALUE}). When the provider set is empty the lazy annotation
 * supplier on the context is never invoked. A type that neither the registry nor any provider can
 * satisfy yields a {@link ParamConverterNotFoundException}.
 */
public final class ParamConversionResolver {

    private final ParamConverterRegistry registry;
    private final List<ParamConverterProvider> providers;

    private ParamConversionResolver(ParamConverterRegistry registry, List<ParamConverterProvider> providers) {
        this.registry = registry;
        this.providers = providers;
    }

    /**
     * Builds a resolver from a native registry and a set of JAX-RS providers. The providers are
     * captured once, ordered by their {@link Priority} value (ascending; absent priority sorts last).
     *
     * @param registry  the native, type-keyed converter registry; never {@code null}
     * @param providers the JAX-RS {@link ParamConverterProvider} set; never {@code null}, may be empty
     * @return a resolver applying the full conversion chain
     */
    public static ParamConversionResolver of(ParamConverterRegistry registry, Set<ParamConverterProvider> providers) {
        List<ParamConverterProvider> ordered = providers.stream()
                .sorted(Comparator.comparingInt(ParamConversionResolver::priorityOf))
                .toList();
        return new ParamConversionResolver(registry, ordered);
    }

    /**
     * A resolver backed solely by the built-in converters — no app {@link ParamConverterBinding}s,
     * no {@link jakarta.ws.rs.ext.ParamConverterProvider}s.
     *
     * @return a built-ins-only resolver
     */
    public static ParamConversionResolver builtins() {
        return ParamConversionResolver.of(ParamConverterRegistry.of(java.util.Set.of()), java.util.Set.of());
    }

    // --- Conversion chain ---

    /**
     * Parses a transport string into the target type described by the context, walking the full
     * conversion chain.
     *
     * @param value the raw transport string; never {@code null}
     * @param ctx   the per-parameter conversion context; never {@code null}
     * @return the parsed typed value
     * @throws ParamConversionException        if a resolved converter cannot parse {@code value}
     * @throws ParamConverterNotFoundException if no converter or provider can satisfy the target type
     */
    public Object fromString(String value, ConversionContext ctx) {
        ParamConverter<?> native_ = registry.find(ctx.rawType()).orElse(null);
        if (native_ != null) {
            return parseWithNative(native_, value, ctx);
        }
        jakarta.ws.rs.ext.ParamConverter<?> jaxrs = resolveJaxRs(ctx);
        if (jaxrs != null) {
            try {
                return jaxrs.fromString(value);
            } catch (RuntimeException e) {
                throw contextualize(e, ctx);
            }
        }
        throw notFound(ctx);
    }

    /**
     * Serializes a typed value into its transport string form, walking the full conversion chain.
     *
     * @param value the typed value to serialize; never {@code null}
     * @param ctx   the per-parameter conversion context; never {@code null}
     * @return the transport string representation
     * @throws ParamConversionException        if a resolved converter cannot serialize {@code value}
     * @throws ParamConverterNotFoundException if no converter or provider can satisfy the target type
     */
    @SuppressWarnings("unchecked")
    public String toString(Object value, ConversionContext ctx) {
        ParamConverter<?> native_ = registry.find(ctx.rawType()).orElse(null);
        if (native_ != null) {
            try {
                return ((ParamConverter<Object>) native_).toString(value);
            } catch (RuntimeException e) {
                throw contextualize(e, ctx);
            }
        }
        jakarta.ws.rs.ext.ParamConverter<?> jaxrs = resolveJaxRs(ctx);
        if (jaxrs != null) {
            try {
                return ((jakarta.ws.rs.ext.ParamConverter<Object>) jaxrs).toString(value);
            } catch (RuntimeException e) {
                throw contextualize(e, ctx);
            }
        }
        throw notFound(ctx);
    }

    /**
     * Reports whether the full chain (native registry plus any JAX-RS providers) can resolve a
     * converter for the target type described by the context. Used for startup/build validation.
     *
     * <p>Probing the provider chain may materialize the context's annotation supplier — acceptable on
     * the validation path.
     *
     * @param ctx the per-parameter conversion context; never {@code null}
     * @return {@code true} if a converter is resolvable, {@code false} otherwise
     */
    public boolean canResolve(ConversionContext ctx) {
        if (registry.find(ctx.rawType()).isPresent()) {
            return true;
        }
        return resolveJaxRs(ctx) != null;
    }

    // --- Internals ---

    /**
     * Parses {@code value} with a native converter, re-contextualizing any parse failure as a
     * {@link ParamConversionException} carrying the resolver's context diagnostics (never the raw
     * value).
     *
     * @param converter the resolved native converter
     * @param value     the raw transport string
     * @param ctx       the per-parameter conversion context
     * @return the parsed value
     */
    private Object parseWithNative(ParamConverter<?> converter, String value, ConversionContext ctx) {
        try {
            return converter.fromString(value);
        } catch (RuntimeException e) {
            throw contextualize(e, ctx);
        }
    }

    /**
     * Wraps a converter failure as a {@link ParamConversionException} carrying the resolver-supplied
     * context (the real parameter name, source, and target type). The resolver is the <em>sole</em>
     * builder of {@link ParamConversionException}: every converter — built-in, enum-synth, or a JAX-RS
     * provider — throws a raw {@link RuntimeException} on a parse failure (a {@link NumberFormatException},
     * a {@link java.time.format.DateTimeParseException}, an {@link IllegalArgumentException}, or a
     * provider's {@link jakarta.ws.rs.WebApplicationException}), and that raw failure becomes the cause
     * here, yielding the uniform conversion-failure contract (FR-015-08a). The raw value is never
     * included in the message. {@link ParamConverterNotFoundException} is raised separately (via
     * {@link #notFound}) and is never routed through this method, so it is never wrapped.
     *
     * @param e   the raw converter failure
     * @param ctx the per-parameter conversion context
     * @return the contextualized exception to throw
     */
    private ParamConversionException contextualize(RuntimeException e, ConversionContext ctx) {
        return new ParamConversionException(
                "Failed to convert parameter '" + ctx.paramName() + "' to "
                        + ctx.rawType().getName(),
                ctx.paramName(),
                ctx.source(),
                ctx.rawType(),
                e);
    }

    /**
     * Walks the ordered JAX-RS provider chain, materializing the context's annotation supplier exactly
     * once, and returns the first non-{@code null} converter — or {@code null} if the provider set is
     * empty or no provider yields a converter.
     *
     * @param ctx the per-parameter conversion context
     * @return the first JAX-RS converter found, or {@code null}
     */
    private jakarta.ws.rs.ext.ParamConverter<?> resolveJaxRs(ConversionContext ctx) {
        if (providers.isEmpty()) {
            return null;
        }
        Annotation[] annotations = ctx.annotationsLazy().get();
        for (ParamConverterProvider provider : providers) {
            jakarta.ws.rs.ext.ParamConverter<?> converter =
                    provider.getConverter(ctx.rawType(), ctx.genericType(), annotations);
            if (converter != null) {
                return converter;
            }
        }
        return null;
    }

    /**
     * Builds a {@link ParamConverterNotFoundException} carrying the context's diagnostics.
     *
     * @param ctx the per-parameter conversion context
     * @return the not-found exception to throw
     */
    private ParamConverterNotFoundException notFound(ConversionContext ctx) {
        return new ParamConverterNotFoundException(
                "No ParamConverter registered for parameter '" + ctx.paramName() + "' of type "
                        + ctx.rawType().getName(),
                ctx.paramName(),
                ctx.source(),
                ctx.rawType());
    }

    /**
     * Returns the {@link Priority} value of a provider, or {@link Integer#MAX_VALUE} when the provider
     * carries no {@code @Priority} annotation (lowest priority).
     *
     * @param provider the provider to inspect
     * @return the priority value (lower runs first)
     */
    private static int priorityOf(ParamConverterProvider provider) {
        Priority priority = provider.getClass().getAnnotation(Priority.class);
        return priority != null ? priority.value() : Integer.MAX_VALUE;
    }
}
