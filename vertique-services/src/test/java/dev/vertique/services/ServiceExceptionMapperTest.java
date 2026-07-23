// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests the {@link ServiceExceptionMapper} layer wrapper for service dispatch. */
class ServiceExceptionMapperTest {

    private ServiceExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ServiceExceptionMapper();
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
    @DisplayName("on() returns this (as the ServiceExceptionMapper subtype) for fluent chaining")
    void onReturnsSelf() {
        ServiceExceptionMapper same = mapper.on(RuntimeException.class, ex -> ex);
        assertSame(mapper, same);
    }

    @Nested
    @DisplayName("Customizer assembly (DispatchModule.serviceExceptionMapper)")
    class CustomizerAssembly {

        @Test
        @DisplayName("Customizers are applied in priority order")
        void customizersPriorityOrder() {
            ServiceExceptionMapperCustomizer lowPriority = new ServiceExceptionMapperCustomizer() {
                @Override
                public void customize(ServiceExceptionMapper mapper) {
                    mapper.on(RuntimeException.class, ex -> new RuntimeException("low"));
                }

                @Override
                public int priority() {
                    return 0;
                }
            };
            ServiceExceptionMapperCustomizer highPriority = new ServiceExceptionMapperCustomizer() {
                @Override
                public void customize(ServiceExceptionMapper mapper) {
                    mapper.on(RuntimeException.class, ex -> new RuntimeException("high"));
                }

                @Override
                public int priority() {
                    return 100;
                }
            };

            // Pass in reverse order to prove sorting works
            ServiceExceptionMapper assembled = DispatchModule.serviceExceptionMapper(Set.of(highPriority, lowPriority));
            Throwable result = assembled.translate(new RuntimeException("test"));
            assertEquals("high", result.getMessage(), "Higher priority customizer should override lower");
        }

        @Test
        @DisplayName("Empty customizer set produces pass-through mapper")
        void emptyCustomizersPassThrough() {
            ServiceExceptionMapper assembled = DispatchModule.serviceExceptionMapper(Set.of());
            var cause = new RuntimeException("unchanged");
            assertSame(cause, assembled.translate(cause));
        }
    }
}
