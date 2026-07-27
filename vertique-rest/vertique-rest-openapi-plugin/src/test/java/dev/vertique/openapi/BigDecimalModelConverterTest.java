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
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BigDecimalModelConverter}, verifying that it resolves {@link BigDecimal}
 * return/field types to the {@code vertique-strict} string wire-form schema, delegates unrelated
 * types unchanged to the next converter in the chain, terminates gracefully when it is last in an
 * empty chain, and composes correctly when chained after {@link FutureModelConverter}.
 */
class BigDecimalModelConverterTest {

    @Test
    @DisplayName("resolve() maps BigDecimal to a string schema with the decimal format, pattern, and max length")
    void bigDecimalType_mapsToStringSchemaWithDecimalFormat() {
        BigDecimalModelConverter converter = new BigDecimalModelConverter();
        AnnotatedType type = new AnnotatedType().type(BigDecimal.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertInstanceOf(StringSchema.class, result);
        assertEquals("string", result.getType());
        assertEquals("decimal", result.getFormat());
        assertEquals("-?[0-9]+(\\.[0-9]+)?", result.getPattern());
        assertEquals(100, result.getMaxLength());
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

        assertInstanceOf(StringSchema.class, result);
        assertEquals("string", result.getType());
        assertEquals("decimal", result.getFormat());
        assertEquals("-?[0-9]+(\\.[0-9]+)?", result.getPattern());
        assertEquals(100, result.getMaxLength());
    }

    /**
     * Fake {@link ModelConverter} that records the {@link AnnotatedType} it was called with and
     * returns a fixed marker {@link Schema}, so tests can assert what {@link BigDecimalModelConverter}
     * delegated downstream without depending on Mockito.
     */
    private static class CapturingModelConverter implements ModelConverter {

        private final Schema<?> marker;
        private AnnotatedType capturedType;

        CapturingModelConverter(Schema<?> marker) {
            this.marker = marker;
        }

        @Override
        public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
            this.capturedType = type;
            return marker;
        }
    }
}
