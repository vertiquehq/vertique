// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonConfigurer}.
 *
 * <p>Tests use the package-private {@code configure(ObjectMapper)} overload to avoid touching the
 * global {@link io.vertx.core.json.jackson.DatabindCodec#mapper()} instance. A fresh
 * {@link ObjectMapper} is created before each test to ensure isolation between test cases.
 */
class JacksonConfigurerTest {

    // --- Fixtures ---

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // --- Tests ---

    @Test
    @DisplayName("Customizers are applied in priority order (ascending)")
    void shouldApplyCustomizersInPriorityOrder() {
        List<String> executionOrder = new ArrayList<>();

        ObjectMapperCustomizer low = new ObjectMapperCustomizer() {
            @Override
            public void customize(ObjectMapper m) {
                executionOrder.add("low");
            }

            @Override
            public int priority() {
                return 10;
            }
        };

        ObjectMapperCustomizer mid = new ObjectMapperCustomizer() {
            @Override
            public void customize(ObjectMapper m) {
                executionOrder.add("mid");
            }

            @Override
            public int priority() {
                return 5;
            }
        };

        ObjectMapperCustomizer high = new ObjectMapperCustomizer() {
            @Override
            public void customize(ObjectMapper m) {
                executionOrder.add("high");
            }

            @Override
            public int priority() {
                return 1;
            }
        };

        JacksonConfigurer configurer = new JacksonConfigurer(Set.of(low, mid, high));
        configurer.configure(mapper);

        assertEquals(List.of("high", "mid", "low"), executionOrder);
    }

    @Test
    @DisplayName("Second configure() call is a no-op (idempotent)")
    void shouldBeIdempotentOnDoubleInvocation() {
        List<String> calls = new ArrayList<>();

        ObjectMapperCustomizer recorder = m -> calls.add("called");

        JacksonConfigurer configurer = new JacksonConfigurer(Set.of(recorder));
        configurer.configure(mapper);
        configurer.configure(mapper);

        assertEquals(1, calls.size(), "Customizer must be invoked exactly once despite two configure() calls");
    }

    @Test
    @DisplayName("Empty customizer set completes without error and leaves mapper unchanged")
    void shouldHandleEmptyCustomizerSet() {
        JacksonConfigurer configurer = new JacksonConfigurer(Set.of());
        assertDoesNotThrow(() -> configurer.configure(mapper));
    }

    @Test
    @DisplayName("Customizer can register a Jackson module, which appears in registered module IDs")
    void shouldRegisterJacksonModule() {
        String moduleId = "test-module";
        SimpleModule module = new SimpleModule(moduleId);

        ObjectMapperCustomizer registrar = m -> m.registerModule(module);

        JacksonConfigurer configurer = new JacksonConfigurer(Set.of(registrar));
        configurer.configure(mapper);

        assertTrue(
                mapper.getRegisteredModuleIds().contains(moduleId),
                "Registered module ID '" + moduleId + "' should be present after customization");
    }

    @Test
    @DisplayName("Customizer can disable a serialization feature")
    void shouldConfigureSerializationFeature() {
        assertTrue(
                mapper.isEnabled(SerializationFeature.INDENT_OUTPUT) == false,
                "INDENT_OUTPUT is disabled by default — precondition for this test");

        // Enable it first so we can verify the customizer turns it back off
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        assertTrue(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT));

        ObjectMapperCustomizer disabler = m -> m.disable(SerializationFeature.INDENT_OUTPUT);

        JacksonConfigurer configurer = new JacksonConfigurer(Set.of(disabler));
        configurer.configure(mapper);

        assertFalse(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT));
    }
}
