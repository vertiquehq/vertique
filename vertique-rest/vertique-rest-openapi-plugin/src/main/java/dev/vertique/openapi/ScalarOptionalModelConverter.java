// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.PrimitiveType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Swagger {@link ModelConverter} that resolves the JDK's three non-generic scalar optionals —
 * {@link OptionalInt}, {@link OptionalLong}, and {@link OptionalDouble} — to the scalar schema
 * matching their actual JSON wire form in the generated OpenAPI specification.
 *
 * <p><strong>Why this converter exists.</strong> swagger-core's Jackson-backed model resolver
 * unwraps generic {@code java.util.Optional<T>} natively: Jackson registers it as a {@code
 * ReferenceType}, so {@code Optional<String>} resolves to {@code T}'s schema directly (a plain
 * {@code string}), with no wrapper object and no {@code required} entry. The three scalar optionals
 * are <em>not</em> generic types and carry no {@code ReferenceType} registration, so the resolver
 * falls back to treating them as ordinary JavaBeans and emits their accessor surface — {@code
 * {empty, present, asInt}} for {@link OptionalInt} — instead of a scalar. At runtime, Jackson's
 * {@code Jdk8Module} (registered by {@code dev.vertique.json.JacksonDefaults}) writes a present
 * scalar optional as a plain JSON number and omits an empty one, so the bean schema is a pure
 * spec/wire mismatch. This converter closes it.
 *
 * <p>Only the three scalar optionals are handled. Generic {@code Optional<T>} is deliberately
 * <strong>not</strong> handled — swagger-core already unwraps it correctly, and intercepting it here
 * would replace an accurate native resolution with a coarser one.
 *
 * <p>Register this converter in the {@code swagger-maven-plugin} configuration, alongside {@link
 * FutureModelConverter}:
 *
 * <pre>{@code
 * <modelConverterClasses>
 *     <modelConverterClass>dev.vertique.openapi.FutureModelConverter</modelConverterClass>
 *     <modelConverterClass>dev.vertique.openapi.ScalarOptionalModelConverter</modelConverterClass>
 * </modelConverterClasses>
 * }</pre>
 *
 * <p><strong>Pairing contract.</strong> Unlike {@link BigDecimalModelConverter}, this converter
 * encodes no profile-specific policy: the scalar wire form it describes is what {@code Jdk8Module}
 * produces for every profile built on {@code dev.vertique.json.JacksonDefaults} — both {@code
 * vertique} and {@code vertique-strict}. Register it whenever any DTO in the scanned resource
 * packages exposes an {@link OptionalInt}, {@link OptionalLong}, or {@link OptionalDouble}
 * property.
 *
 * <p><strong>Presence semantics.</strong> A scalar optional property is not marked {@code required}
 * and not marked {@code nullable}: an empty value is <em>omitted</em> from the payload, it is not
 * written as JSON {@code null}. Clients omit the property rather than sending {@code null} — under
 * the {@code web-validation} strategy an explicit {@code null} is rejected by the spec gate before
 * it ever reaches Jackson.
 *
 * <p><strong>Chain-end contract:</strong> delegates to the next converter via {@link
 * ConverterChain#delegate}, which returns {@code null} rather than throwing when this converter is
 * last in the configured chain.
 */
public final class ScalarOptionalModelConverter implements ModelConverter {

    /**
     * Maps each JDK scalar optional to the primitive class whose {@link PrimitiveType} schema
     * matches its JSON wire form.
     */
    private static final Map<Class<?>, Class<?>> SCALAR_OPTIONALS =
            Map.of(OptionalInt.class, int.class, OptionalLong.class, long.class, OptionalDouble.class, double.class);

    /**
     * Resolves the schema for the given type. {@link OptionalInt} resolves to {@code
     * integer/int32}, {@link OptionalLong} to {@code integer/int64}, and {@link OptionalDouble} to
     * {@code number/double}, each produced via {@link PrimitiveType#fromType(Class)} — the same
     * primitive schema table used elsewhere in this module (see {@link RequestParamsExtension}).
     * Every other type — including generic {@link Optional}, which swagger-core unwraps natively —
     * is delegated to the next converter in the chain.
     *
     * @param type the annotated type being resolved
     * @param context the current model converter context
     * @param chain the remaining converters in the resolution chain
     * @return the scalar schema for a JDK scalar optional, or the result of the next converter in
     *     {@code chain}, or {@code null} if the chain is exhausted
     */
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null) {
            Class<?> mapped = SCALAR_OPTIONALS.get(javaType.getRawClass());
            if (mapped != null) {
                return PrimitiveType.fromType(mapped).createProperty();
            }
        }
        return ConverterChain.delegate(type, context, chain);
    }
}
