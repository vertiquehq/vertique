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
import java.math.BigDecimal;
import java.util.Iterator;

/**
 * Swagger {@link ModelConverter} that resolves {@link BigDecimal} return/field types to the
 * {@code vertique-strict} string wire-form schema in the generated OpenAPI specification.
 *
 * <p>Without this converter, swagger-maven-plugin emits {@code BigDecimal} as a JSON {@code
 * number} schema — the default Jackson wire form. This converter instead emits a {@code string}
 * schema so the generated spec matches applications that opt into the {@code vertique-strict}
 * JSON profile, where {@code BigDecimal} is serialized/deserialized as a plain decimal string
 * (see {@code dev.vertique.json.VertiqueStrictJsonMapperProfile} in {@code vertique-json}).
 *
 * <p>Register this converter in the {@code swagger-maven-plugin} configuration, typically
 * alongside {@link FutureModelConverter}:
 *
 * <pre>{@code
 * <modelConverterClasses>
 *     <modelConverterClass>dev.vertique.openapi.FutureModelConverter</modelConverterClass>
 *     <modelConverterClass>dev.vertique.openapi.BigDecimalModelConverter</modelConverterClass>
 * </modelConverterClasses>
 * }</pre>
 *
 * <p><strong>Pairing contract.</strong> This converter is spec-global: it applies to every {@code
 * BigDecimal} <em>schema</em> occurrence Swagger resolves (return types, properties, collection
 * elements), producing one decimal wire-form policy per spec. It does <strong>not</strong> apply to
 * {@code Map<BigDecimal, ?>} <strong>keys</strong> — Swagger resolves a map to {@code
 * additionalProperties} describing only the value type, so this converter is never asked to
 * resolve the key type; OAS 3.0.1 has no {@code propertyNames} keyword, so the generated spec
 * leaves decimal map keys unbounded even though the runtime key deserializer still rejects a
 * malformed key with a 400. It is <strong>required</strong> when the application selects the
 * {@code vertique-strict} JSON profile, so the generated spec's schema type (a decimal string)
 * matches the runtime wire form the profile actually produces. Mixing string and number decimal
 * wire forms within a single application's spec is <strong>unsupported</strong> by this converter —
 * an application with a mixed profile posture (some {@code BigDecimal} fields as numbers, others as
 * strings) should not register this converter and instead annotate the string-form properties
 * individually with {@code @Schema(type = "string", format = "decimal")}, which is the escape hatch
 * for mixed-profile applications.
 *
 * <p><strong>Grammar mirror.</strong> The emitted {@code pattern} and {@code maxLength} describe the
 * same plain decimal literal grammar enforced at runtime by {@code
 * dev.vertique.json.BigDecimalStrictStringDeserializer} (no exponent notation, at most 100
 * characters) — but the spec-side pattern is deliberately <strong>anchored</strong>
 * ({@code ^-?[0-9]+(\.[0-9]+)?$}), unlike the deserializer's unanchored {@code -?[0-9]+(\.[0-9]+)?}.
 * JSON Schema {@code pattern} has ECMA-262 <em>search</em> semantics — like {@link
 * java.util.regex.Matcher#find()} — so an unanchored pattern would match any string that merely
 * <em>contains</em> a digit run (e.g. {@code "not 1.50 a number"} would incorrectly validate).
 * {@link java.util.regex.Matcher#matches()}, which the deserializer uses, is implicitly anchored to
 * the whole input, so it needs no {@code ^}/{@code $}. Anchoring the spec pattern is what makes
 * generated client validation reject the malformed inputs the server would reject — but honestly,
 * it is not a perfect substitute for the runtime check: a JVM-based ECMA/Java regex validator can
 * still accept a value with a trailing newline (e.g. {@code "1.50\n"}), because Java's {@code $} — by
 * default — matches either at the end of input or immediately before a final line terminator. The
 * deserializer's own unanchored {@code matches()} call has no such gap: it requires the entire input,
 * including the trailing {@code \n}, to fall inside the grammar, so that same value is rejected with
 * a 400.
 *
 * <p><strong>Chain-end contract:</strong> delegates to the next converter via {@link
 * ConverterChain#delegate}, which returns {@code null} rather than throwing when this converter is
 * last in the configured chain.
 */
public final class BigDecimalModelConverter implements ModelConverter {

    /** Maximum accepted length, in characters, of the decimal literal (mirrors the deserializer). */
    private static final int MAX_LENGTH = 100;

    /**
     * The plain decimal literal grammar, anchored with {@code ^}/{@code $} because JSON Schema
     * {@code pattern} has ECMA-262 search (unanchored) semantics — see the class-level "Grammar
     * mirror" note.
     */
    private static final String DECIMAL_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$";

    /**
     * Resolves the schema for the given type. If the type is {@link BigDecimal}, this converter
     * returns a string schema with the {@code decimal} format, the anchored plain decimal pattern,
     * and the matching max length. Otherwise, the call is delegated to the next converter in the
     * chain.
     *
     * @param type the annotated type being resolved
     * @param context the current model converter context
     * @param chain the remaining converters in the resolution chain
     * @return a {@link StringSchema} for {@link BigDecimal} types, or the result of the next
     *     converter in {@code chain}, or {@code null} if the chain is exhausted
     */
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null && BigDecimal.class.equals(javaType.getRawClass())) {
            return new StringSchema().format("decimal").pattern(DECIMAL_PATTERN).maxLength(MAX_LENGTH);
        }
        return ConverterChain.delegate(type, context, chain);
    }
}
