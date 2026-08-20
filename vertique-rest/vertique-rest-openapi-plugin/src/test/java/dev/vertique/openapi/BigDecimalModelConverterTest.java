// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BigDecimalModelConverter}, verifying that it resolves {@link BigDecimal}
 * return/field types to the {@code vertique-strict} string wire-form schema, delegates unrelated
 * types (including {@code Map<BigDecimal, ?>}, whose keys this converter never bounds — see the
 * module doc's map-key caveat) unchanged to the next converter in the chain, terminates gracefully
 * when it is last in an empty chain, and composes correctly when chained after
 * {@link FutureModelConverter}.
 */
class BigDecimalModelConverterTest {

    @Test
    @DisplayName("resolve() maps BigDecimal to a string schema with the decimal format, pattern, and max length")
    void bigDecimalType_mapsToStringSchemaWithDecimalFormat() {
        BigDecimalModelConverter converter = new BigDecimalModelConverter();
        AnnotatedType type = new AnnotatedType().type(BigDecimal.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertDecimalStringSchema(result);
    }

    @Test
    @DisplayName("resolve() passes non-BigDecimal types through to the next converter unchanged")
    void nonBigDecimalType_delegatesToChain() {
        BigDecimalModelConverter converter = new BigDecimalModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        AnnotatedType type = new AnnotatedType().type(String.class);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertSame(type, next.capturedType);
    }

    @Test
    @DisplayName(
            "resolve() returns null instead of throwing when last in an empty converter chain for a non-BigDecimal type")
    void lastInChain_nonBigDecimal_returnsNull() {
        BigDecimalModelConverter converter = new BigDecimalModelConverter();
        AnnotatedType type = new AnnotatedType().type(String.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertNull(result);
    }

    /**
     * Exercises the {@code [FutureModelConverter, BigDecimalModelConverter]} chain — the order a
     * {@code <modelConverterClasses>} block <em>declares</em>, not the order the plugin registers.
     * {@code ModelConverters#addConverter} prepends, so a declared order composes back-to-front and
     * the registered chain puts {@link BigDecimalModelConverter} <em>ahead</em> of the
     * {@code Future} unwrap, where it can no longer see the unwrapped {@link BigDecimal}.
     *
     * <p>This is therefore a converter-contract test — it proves {@link BigDecimalModelConverter}
     * resolves a {@link BigDecimal} handed to it by an upstream unwrapping converter — and not a
     * model of the production chain. What the production chain actually produces for
     * {@code Future<BigDecimal>} is pinned by
     * {@code OpenApiSchemaCompositionTest.RegistrationOrder}, and it is a bare {@code number}
     * (see {@code vertiquehq/vertique-dev#395}).
     */
    @Test
    @DisplayName(
            "BigDecimal nested inside Future<T> resolves to the decimal string schema when chained after FutureModelConverter")
    void bigDecimalInsideFuture_resolvedWhenChainedAfterFutureConverter() {
        FutureModelConverter futureConverter = new FutureModelConverter();
        BigDecimalModelConverter bigDecimalConverter = new BigDecimalModelConverter();
        Iterator<ModelConverter> chain =
                List.<ModelConverter>of(bigDecimalConverter).iterator();

        var futureOfBigDecimal = io.swagger.v3.core.util.Json.mapper()
                .getTypeFactory()
                .constructParametricType(io.vertx.core.Future.class, BigDecimal.class);
        AnnotatedType type = new AnnotatedType().type(futureOfBigDecimal);

        Schema<?> result = futureConverter.resolve(type, null, chain);

        assertDecimalStringSchema(result);
    }

    @Test
    @DisplayName(
            "resolve() on a Map<BigDecimal, ?> passes through to the next converter unchanged (keys are not bounded)")
    void mapOfBigDecimalKeys_delegatesToChainUnchanged() {
        BigDecimalModelConverter converter = new BigDecimalModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        var mapType = io.swagger.v3.core.util.Json.mapper()
                .getTypeFactory()
                .constructMapType(Map.class, BigDecimal.class, String.class);
        AnnotatedType type = new AnnotatedType().type(mapType);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertSame(type, next.capturedType);
    }

    /**
     * Asserts that the given schema is the decimal string schema this test class expects:
     * {@code string} type, {@code decimal} format, the plain decimal pattern, and the 100-character
     * max length.
     *
     * @param schema the schema to assert against
     */
    private static void assertDecimalStringSchema(Schema<?> schema) {
        assertInstanceOf(StringSchema.class, schema);
        assertEquals("string", schema.getType());
        assertEquals("decimal", schema.getFormat());
        assertEquals("^-?[0-9]+(\\.[0-9]+)?$", schema.getPattern());
        assertEquals(100, schema.getMaxLength());
    }
}
