// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.vertx.core.Future;
import java.util.Iterator;

/**
 * Swagger {@link ModelConverter} that unwraps {@code io.vertx.core.Future<T>} return types to
 * their inner type {@code T} in the generated OpenAPI specification.
 *
 * <p>Without this converter, swagger-maven-plugin would emit {@code Future} as an opaque schema;
 * with it, the spec reflects the actual response payload type (e.g. a resource record or list) so
 * generated client code and API documentation are accurate.
 *
 * <p>Register this converter in the {@code swagger-maven-plugin} configuration:
 *
 * <pre>{@code
 * <modelConverterClasses>
 *     dev.vertique.openapi.FutureModelConverter
 * </modelConverterClasses>
 * }</pre>
 *
 * <p><strong>Chain-end contract:</strong> delegates to the next converter via {@link
 * ConverterChain#delegate}, which returns {@code null} rather than throwing when this converter is
 * last in the configured chain.
 */
public class FutureModelConverter implements ModelConverter {

    /**
     * If the given type is {@code Future<T>}, returns an {@link AnnotatedType} wrapping {@code T}.
     * Otherwise returns {@code type} unchanged.
     *
     * @param type the annotated type to inspect
     * @return the unwrapped inner type when {@code type} is a {@link Future}, otherwise {@code
     *     type}
     */
    private AnnotatedType futureValue(AnnotatedType type) {
        JavaType _type = Json.mapper().constructType(type.getType());

        if (_type == null) {
            return type;
        }

        if (!Future.class.isAssignableFrom(_type.getRawClass())) {
            return type;
        }

        var valueType = _type.findTypeParameters(_type.getRawClass())[0];
        return new AnnotatedType().type(valueType).resolveAsRef(true);
    }

    /**
     * Resolves the schema for the given type. If the type is {@code Future<T>}, it is unwrapped to
     * {@code T} before delegating to the next converter in {@code chain}. Otherwise the type is
     * passed through unchanged.
     *
     * @param type the annotated type being resolved
     * @param context the current model converter context
     * @param chain the remaining converters in the resolution chain
     * @return the schema produced by the next converter in {@code chain}, or {@code null} if the
     *     chain is exhausted
     */
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        AnnotatedType unwrapped = futureValue(type);
        return ConverterChain.delegate(unwrapped, context, chain);
    }
}
