// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.convert;

import dev.vertique.rest.client.meta.ClientParamMeta;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamSource;
import jakarta.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for {@link ConversionContext} instances on the REST client outbound serialization path.
 *
 * <p>Mirrors {@code dev.vertique.rest.jaxrs.convert.ConversionContexts} from the JAX-RS inbound path:
 * it maps the client-side {@link ClientParamMeta.ParamSource} to the framework-neutral
 * {@link ParamSource} and builds the {@link ConversionContext} that the {@link
 * dev.vertique.rest.core.convert.ParamConversionResolver} consumes.
 *
 * <p>Only conversion-applicable sources (PATH, QUERY, HEADER, COOKIE) are valid inputs.
 * BODY, BEAN_PARAM, and URL are never passed to the resolver.
 */
public final class ClientConversionContexts {

    private ClientConversionContexts() {}

    /**
     * Builds a {@link ConversionContext} for a scalar {@link ClientParamMeta} whose source is
     * conversion-applicable (PATH, QUERY, HEADER, or COOKIE).
     *
     * <p>The annotation supplier is derived from {@link ClientParamMeta#annotationsLazy()}, which
     * returns the parameter's declared annotations from the composed {@code ParameterMetadata} view.
     *
     * @param pm the client parameter metadata; source must be PATH, QUERY, HEADER, or COOKIE
     * @return the conversion context for this parameter
     * @throws IllegalArgumentException if the source is not conversion-applicable
     */
    public static ConversionContext forParam(ClientParamMeta pm) {
        return new ConversionContext(
                pm.name(),
                toSource(pm.source()),
                pm.type(),
                pm.genericType(),
                pm.componentType(),
                pm.annotationsLazy());
    }

    /**
     * Builds a {@link ConversionContext} for a single collection <em>element</em> of a
     * collection-valued {@link ClientParamMeta}. Used when expanding a {@code List<UUID>} or
     * similar collection parameter element-by-element through the resolver.
     *
     * <p>The result context targets the component type (e.g. {@code UUID}), not the collection
     * type; the diagnostics ({@code paramName}, {@code source}) still reference the declaring
     * parameter, matching the JAX-RS inbound path's convention.
     *
     * @param pm            the collection parameter metadata
     * @param componentType the element type to convert (must equal {@code pm.componentType()})
     * @return the per-element conversion context
     * @throws IllegalArgumentException if the source is not conversion-applicable
     */
    public static ConversionContext forComponent(ClientParamMeta pm, Class<?> componentType) {
        return new ConversionContext(
                pm.name(), toSource(pm.source()), componentType, componentType, null, pm.annotationsLazy());
    }

    /**
     * Maps a client-side {@link ClientParamMeta.ParamSource} to the framework-neutral
     * {@link ParamSource}.
     *
     * @param source the client parameter source; must be a conversion-applicable source
     * @return the matching framework-neutral {@link ParamSource}
     * @throws IllegalArgumentException if {@code source} is not conversion-applicable
     */
    public static ParamSource toSource(ClientParamMeta.ParamSource source) {
        return switch (source) {
            case PATH -> ParamSource.PATH;
            case QUERY -> ParamSource.QUERY;
            case HEADER -> ParamSource.HEADER;
            case COOKIE -> ParamSource.COOKIE;
            default -> throw new IllegalArgumentException("Not a conversion-applicable param source: " + source);
        };
    }

    /**
     * Returns the elements of a collection- or array-valued parameter for element-by-element
     * expansion, or {@code null} if {@code value} is neither a {@link Iterable} nor a Java array.
     *
     * <p>{@link dev.vertique.rest.client.meta.ClientInterfaceScanner#resolveComponentType} resolves a
     * non-null {@link ClientParamMeta#componentType()} for both {@code List<T>} and {@code T[]}
     * parameters, but the two runtime shapes require different element access: a {@link Iterable} is
     * iterated directly, while an array (including a primitive array, which is not an
     * {@code Object[]}) must be read via {@link Array#get(Object, int)}. Centralizing the shape check
     * here means every collection-expansion call site (the dispatcher's {@code applyQueryParam} and
     * {@link dev.vertique.rest.client.RestClientRequestFactory}'s query/path/header/cookie
     * collectors) handles both shapes identically instead of only the {@link Iterable} case.
     *
     * @param value the raw parameter value; may be {@code null}
     * @return the elements as a {@link List}, or {@code null} if {@code value} is not a collection or
     *     array
     */
    @Nullable
    public static List<Object> elementsOf(@Nullable Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<Object> elements = new ArrayList<>();
            for (Object element : iterable) {
                elements.add(element);
            }
            return elements;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> elements = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                elements.add(Array.get(value, i));
            }
            return elements;
        }
        return null;
    }
}
