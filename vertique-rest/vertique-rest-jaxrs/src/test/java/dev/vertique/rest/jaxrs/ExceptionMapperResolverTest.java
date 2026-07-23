// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExceptionMapperResolver}.
 */
class ExceptionMapperResolverTest {

    static class DirectMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return Response.status(400).build();
        }
    }

    abstract static class AbstractMapper<T extends Throwable> implements ExceptionMapper<T> {
        @Override
        public Response toResponse(T exception) {
            return Response.status(500).build();
        }
    }

    static class ConcreteMapper extends AbstractMapper<IllegalStateException>
            implements ExceptionMapper<IllegalStateException> {}

    static class AnotherMapper implements ExceptionMapper<NullPointerException> {
        @Override
        public Response toResponse(NullPointerException exception) {
            return Response.status(500).build();
        }
    }

    @Test
    @DisplayName("Should resolve ExceptionMapper<T> and return map entry")
    void shouldResolveDirectMapper() {
        DirectMapper mapper = new DirectMapper();
        Map<Class<? extends Throwable>, ExceptionMapper<?>> result = ExceptionMapperResolver.resolve(Set.of(mapper));

        assertEquals(1, result.size());
        assertTrue(result.containsKey(IllegalArgumentException.class));
        assertSame(mapper, result.get(IllegalArgumentException.class));
    }

    @Test
    @DisplayName("Should resolve from abstract base class hierarchy")
    void shouldResolveFromAbstractBase() {
        ConcreteMapper mapper = new ConcreteMapper();
        Map<Class<? extends Throwable>, ExceptionMapper<?>> result = ExceptionMapperResolver.resolve(Set.of(mapper));

        assertEquals(1, result.size());
        assertTrue(result.containsKey(IllegalStateException.class));
        assertSame(mapper, result.get(IllegalStateException.class));
    }

    @Test
    @DisplayName("Should return empty map for empty input")
    void shouldReturnEmptyForEmptyInput() {
        Map<Class<? extends Throwable>, ExceptionMapper<?>> result = ExceptionMapperResolver.resolve(Set.of());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should resolve all mappers in a set")
    void shouldResolveMultipleMappers() {
        DirectMapper direct = new DirectMapper();
        AnotherMapper another = new AnotherMapper();

        Map<Class<? extends Throwable>, ExceptionMapper<?>> result =
                ExceptionMapperResolver.resolve(Set.of(direct, another));

        assertEquals(2, result.size());
        assertSame(direct, result.get(IllegalArgumentException.class));
        assertSame(another, result.get(NullPointerException.class));
    }
}
