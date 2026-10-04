// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.vertx.core.Future;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FutureModelConverter}, verifying that it unwraps {@code Future<T>} return
 * types to {@code T} and restarts resolution so the rest of the chain sees the inner type, and that
 * it terminates gracefully (returns {@code null}) rather than throwing when it is the last
 * converter in the chain for a non-{@code Future} type.
 */
class FutureModelConverterTest {

    @Test
    @DisplayName("resolve() returns null instead of throwing when last in an empty converter chain")
    void lastInChain_returnsNullInsteadOfThrowing() {
        FutureModelConverter converter = new FutureModelConverter();
        AnnotatedType type = new AnnotatedType().type(String.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = assertDoesNotThrow(() -> converter.resolve(type, null, emptyChain));

        assertNull(result);
    }

    @Test
    @DisplayName("resolve() unwraps Future<T> and restarts resolution so the chain sees T")
    void futureType_isUnwrappedBeforeDelegation() {
        FutureModelConverter converter = new FutureModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        ModelConverterContextImpl context = new ModelConverterContextImpl(List.of(converter, next));

        JavaType futureOfString = Json.mapper().getTypeFactory().constructParametricType(Future.class, String.class);

        Schema<?> result = context.resolve(new AnnotatedType().type(futureOfString));

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertEquals(
                String.class,
                Json.mapper().constructType(next.capturedType.getType()).getRawClass());
    }

    @Test
    @DisplayName("resolve() passes non-Future types through to the next converter unchanged")
    void nonFutureType_passedThroughUnchanged() {
        FutureModelConverter converter = new FutureModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        AnnotatedType type = new AnnotatedType().type(String.class);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertEquals(String.class, next.capturedType.getType());
    }
}
