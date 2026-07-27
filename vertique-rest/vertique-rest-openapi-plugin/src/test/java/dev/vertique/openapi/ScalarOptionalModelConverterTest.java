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
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScalarOptionalModelConverter}, verifying that it resolves the three
 * non-generic JDK scalar optionals ({@link OptionalInt}, {@link OptionalLong}, {@link
 * OptionalDouble}) to the scalar schema matching their Jackson {@code Jdk8Module} wire form,
 * delegates every other type (including generic {@link Optional}, which swagger-core unwraps
 * natively) unchanged to the next converter in the chain, and terminates gracefully when it is last
 * in an empty chain.
 */
class ScalarOptionalModelConverterTest {

    @Test
    @DisplayName("resolve() maps OptionalInt to an integer schema with the int32 format")
    void optionalInt_mapsToIntegerInt32Schema() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
        AnnotatedType type = new AnnotatedType().type(OptionalInt.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertInstanceOf(IntegerSchema.class, result);
        assertEquals("integer", result.getType());
        assertEquals("int32", result.getFormat());
    }

    @Test
    @DisplayName("resolve() maps OptionalLong to an integer schema with the int64 format")
    void optionalLong_mapsToIntegerInt64Schema() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
        AnnotatedType type = new AnnotatedType().type(OptionalLong.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertInstanceOf(IntegerSchema.class, result);
        assertEquals("integer", result.getType());
        assertEquals("int64", result.getFormat());
    }

    @Test
    @DisplayName("resolve() maps OptionalDouble to a number schema with the double format")
    void optionalDouble_mapsToNumberDoubleSchema() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
        AnnotatedType type = new AnnotatedType().type(OptionalDouble.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertInstanceOf(NumberSchema.class, result);
        assertEquals("number", result.getType());
        assertEquals("double", result.getFormat());
    }

    @Test
    @DisplayName("resolve() passes non-scalar-optional types through to the next converter unchanged")
    void nonScalarOptionalType_delegatesToChain() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
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
    @DisplayName("resolve() leaves generic Optional<T> to the chain — swagger-core unwraps it natively")
    void genericOptional_delegatesToChain() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        var optionalOfString = io.swagger.v3.core.util.Json.mapper()
                .getTypeFactory()
                .constructParametricType(Optional.class, String.class);
        AnnotatedType type = new AnnotatedType().type(optionalOfString);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertSame(type, next.capturedType);
    }

    @Test
    @DisplayName(
            "resolve() returns null instead of throwing when last in an empty converter chain for a non-scalar-optional type")
    void lastInChain_nonScalarOptional_returnsNull() {
        ScalarOptionalModelConverter converter = new ScalarOptionalModelConverter();
        AnnotatedType type = new AnnotatedType().type(String.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertNull(result);
    }

    /**
     * Fake {@link ModelConverter} that records the {@link AnnotatedType} it was called with and
     * returns a fixed marker {@link Schema}, so tests can assert what {@link
     * ScalarOptionalModelConverter} delegated downstream without depending on Mockito.
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
