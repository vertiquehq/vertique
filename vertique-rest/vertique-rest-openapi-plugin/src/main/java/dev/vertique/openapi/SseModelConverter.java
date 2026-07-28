// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.vertx.core.streams.ReadStream;
import java.util.Iterator;

/**
 * Swagger {@link ModelConverter} that resolves {@code ReadStream<SseEvent>} return types
 * to a plain string schema in the generated OpenAPI specification.
 *
 * <p>SSE endpoints return a text stream, not a JSON object. This converter ensures that
 * the generated schema correctly represents the response as a string rather than
 * attempting to describe the internal {@code SseEvent} structure.
 *
 * <p>Register this converter alongside {@link FutureModelConverter} in the
 * {@code swagger-maven-plugin} configuration:
 *
 * <pre>{@code
 * <modelConverterClasses>
 *     <modelConverterClass>dev.vertique.openapi.FutureModelConverter</modelConverterClass>
 *     <modelConverterClass>dev.vertique.openapi.SseModelConverter</modelConverterClass>
 * </modelConverterClasses>
 * }</pre>
 *
 * <p>Note: {@code Future<ReadStream<SseEvent>>} is already unwrapped to
 * {@code ReadStream<SseEvent>} by {@link FutureModelConverter} before this converter
 * runs, so this converter only needs to handle the {@code ReadStream} level.
 */
public class SseModelConverter implements ModelConverter {

    /**
     * Resolves the schema for the given type. If the type is a {@link ReadStream} of
     * {@code SseEvent}, this converter returns a string schema (representing an SSE text
     * stream). Otherwise — including a {@code ReadStream} of any other element type — the
     * call is delegated to the next converter in the chain.
     *
     * @param type the annotated type being resolved
     * @param context the current model converter context
     * @param chain the remaining converters in the resolution chain
     * @return a {@link StringSchema} for {@code ReadStream<SseEvent>} types, or the result of
     *     the next converter in {@code chain}, or {@code null} if the chain is exhausted
     */
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null
                && ReadStream.class.isAssignableFrom(javaType.getRawClass())
                && isSseEventStream(javaType)) {
            return new StringSchema().description("SSE event stream");
        }
        return ConverterChain.delegate(type, context, chain);
    }

    /**
     * Returns {@code true} if the given type is a {@code ReadStream<SseEvent>}. Checks the type
     * argument by class name to avoid a compile-time dependency on the {@code rest-core} module.
     *
     * @param javaType the resolved Java type
     * @return {@code true} if the type parameter is {@code dev.vertique.rest.core.sse.SseEvent}
     */
    private static boolean isSseEventStream(JavaType javaType) {
        if (!javaType.hasGenericTypes()) {
            return false;
        }
        JavaType typeArg = javaType.containedType(0);
        return typeArg != null
                && "dev.vertique.rest.core.sse.SseEvent"
                        .equals(typeArg.getRawClass().getName());
    }
}
