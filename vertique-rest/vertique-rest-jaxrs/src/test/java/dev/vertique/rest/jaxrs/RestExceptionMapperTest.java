// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests the {@link RestExceptionMapper} layer wrapper for the REST error pipeline. */
class RestExceptionMapperTest {

    private RestExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RestExceptionMapper();
    }

    @Test
    @DisplayName("Should translate registered exception type")
    void shouldTranslate() {
        mapper.on(IllegalArgumentException.class, ex -> new RuntimeException("mapped: " + ex.getMessage()));

        Throwable result = mapper.translate(new IllegalArgumentException("bad"));
        assertInstanceOf(RuntimeException.class, result);
        assertEquals("mapped: bad", result.getMessage());
    }

    @Test
    @DisplayName("Should return original for unregistered type")
    void shouldPassThrough() {
        var cause = new RuntimeException("unregistered");
        assertSame(cause, mapper.translate(cause));
    }

    @Test
    @DisplayName("Should walk hierarchy to find translator")
    void shouldWalkHierarchy() {
        mapper.on(RuntimeException.class, ex -> new IllegalStateException("translated"));

        Throwable result = mapper.translate(new NumberFormatException("bad number"));
        assertInstanceOf(IllegalStateException.class, result);
    }

    @Test
    @DisplayName("on() returns this (as the RestExceptionMapper subtype) for fluent chaining")
    void onReturnsSelf() {
        RestExceptionMapper same = mapper.on(RuntimeException.class, ex -> ex);
        assertSame(mapper, same);
    }

    @Nested
    @DisplayName("Customizer assembly (RestModule.restExceptionMapper)")
    class CustomizerAssembly {

        @Test
        @DisplayName("Customizers are applied in priority order")
        void customizersPriorityOrder() {
            RestExceptionMapperCustomizer lowPriority = new RestExceptionMapperCustomizer() {
                @Override
                public void customize(RestExceptionMapper mapper) {
                    mapper.on(RuntimeException.class, ex -> new RuntimeException("low"));
                }

                @Override
                public int priority() {
                    return 0;
                }
            };
            RestExceptionMapperCustomizer highPriority = new RestExceptionMapperCustomizer() {
                @Override
                public void customize(RestExceptionMapper mapper) {
                    mapper.on(RuntimeException.class, ex -> new RuntimeException("high"));
                }

                @Override
                public int priority() {
                    return 100;
                }
            };

            // Pass in reverse order to prove sorting works
            RestExceptionMapper assembled = RestModule.restExceptionMapper(Set.of(highPriority, lowPriority));
            Throwable result = assembled.translate(new RuntimeException("test"));
            assertEquals("high", result.getMessage(), "Higher priority customizer should override lower");
        }

        @Test
        @DisplayName("Last-wins semantics for same exception type at same priority")
        void lastWinsWithinSamePriority() {
            // Two named classes at same priority — class name determines order
            RestExceptionMapperCustomizer customizerA = new ACustomizer();
            RestExceptionMapperCustomizer customizerB = new BCustomizer();

            RestExceptionMapper assembled = RestModule.restExceptionMapper(Set.of(customizerA, customizerB));
            Throwable result = assembled.translate(new RuntimeException("test"));
            // BCustomizer sorts after ACustomizer by class name, so B wins
            assertEquals("B", result.getMessage());
        }

        @Test
        @DisplayName("Empty customizer set produces pass-through mapper")
        void emptyCustomizersPassThrough() {
            RestExceptionMapper assembled = RestModule.restExceptionMapper(Set.of());
            var cause = new RuntimeException("unchanged");
            assertSame(cause, assembled.translate(cause));
        }
    }

    // Named classes for deterministic class-name ordering
    static class ACustomizer implements RestExceptionMapperCustomizer {
        @Override
        public void customize(RestExceptionMapper mapper) {
            mapper.on(RuntimeException.class, ex -> new RuntimeException("A"));
        }
    }

    static class BCustomizer implements RestExceptionMapperCustomizer {
        @Override
        public void customize(RestExceptionMapper mapper) {
            mapper.on(RuntimeException.class, ex -> new RuntimeException("B"));
        }
    }
}
