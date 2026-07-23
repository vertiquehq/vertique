// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.failure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the {@link FailureMapper} hierarchy-aware, context-aware translator registry engine. */
class FailureMapperTest {

    private FailureMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new FailureMapper();
    }

    @Test
    @DisplayName("Should find translator for exact type")
    void shouldFindExactType() {
        FailureTranslator<IllegalArgumentException> t = ex -> new RuntimeException("mapped");
        mapper.on(IllegalArgumentException.class, t);

        assertSame(t, mapper.findTranslator(IllegalArgumentException.class));
    }

    @Test
    @DisplayName("Should walk superclass hierarchy to find translator")
    void shouldWalkHierarchy() {
        FailureTranslator<RuntimeException> t = ex -> new RuntimeException("mapped");
        mapper.on(RuntimeException.class, t);

        // NumberFormatException extends IllegalArgumentException extends RuntimeException
        assertSame(t, mapper.findTranslator(NumberFormatException.class));
    }

    @Test
    @DisplayName("Should return null for unregistered type")
    void shouldReturnNullForUnregistered() {
        assertNull(mapper.findTranslator(RuntimeException.class));
    }

    @Test
    @DisplayName("Should cache hierarchy lookups")
    void shouldCacheLookups() {
        FailureTranslator<RuntimeException> t = ex -> new RuntimeException("mapped");
        mapper.on(RuntimeException.class, t);

        assertSame(
                mapper.findTranslator(NumberFormatException.class), mapper.findTranslator(NumberFormatException.class));
    }

    @Test
    @DisplayName("Last registration wins for same type")
    void lastRegistrationWins() {
        FailureTranslator<RuntimeException> first = ex -> new RuntimeException("first");
        FailureTranslator<RuntimeException> second = ex -> new RuntimeException("second");
        mapper.on(RuntimeException.class, first);
        mapper.on(RuntimeException.class, second);

        assertSame(second, mapper.findTranslator(RuntimeException.class));
    }

    @Test
    @DisplayName("on() returns this for fluent chaining")
    void onReturnsSelf() {
        assertSame(mapper, mapper.on(RuntimeException.class, ex -> new RuntimeException()));
    }

    @Test
    @DisplayName("New registration clears stale cache entries")
    void shouldClearCacheOnRegistration() {
        FailureTranslator<RuntimeException> broad = ex -> new RuntimeException("broad");
        mapper.on(RuntimeException.class, broad);

        // Cache the lookup for IllegalArgumentException → RuntimeException translator
        assertSame(broad, mapper.findTranslator(IllegalArgumentException.class));

        // Register a more specific translator
        FailureTranslator<IllegalArgumentException> specific = ex -> new RuntimeException("specific");
        mapper.on(IllegalArgumentException.class, specific);

        // Cache should be cleared — specific translator should win now
        assertSame(specific, mapper.findTranslator(IllegalArgumentException.class));
    }

    @Test
    @DisplayName("Empty mapper returns null for any type")
    void emptyMapperReturnsNull() {
        assertNull(mapper.findTranslator(Exception.class));
        assertNull(mapper.findTranslator(Throwable.class));
        assertNull(mapper.findTranslator(RuntimeException.class));
    }

    @Test
    @DisplayName("Plain translator ignores the supplied context")
    void plainTranslatorIgnoresContext() {
        FailureTranslator<IllegalArgumentException> t = ex -> new RuntimeException("x");
        mapper.on(IllegalArgumentException.class, t);

        Throwable result = mapper.translate(new IllegalArgumentException("orig"), "ctx");

        assertEquals("x", result.getMessage());
    }

    @Test
    @DisplayName("Context-aware translator receives the supplied context")
    void contextAwareTranslatorReceivesContext() {
        ContextAwareFailureTranslator<IllegalArgumentException> t = (ex, ctx) -> new RuntimeException(ctx);
        mapper.on(IllegalArgumentException.class, t);

        Throwable result = mapper.translate(new IllegalArgumentException("orig"), "ctx");

        assertEquals("ctx", result.getMessage());
    }

    @Test
    @DisplayName("One-arg translate defaults context to the throwable's message")
    void oneArgTranslateDefaultsContextToMessage() {
        AtomicReference<String> captured = new AtomicReference<>();
        ContextAwareFailureTranslator<IllegalArgumentException> t = (ex, ctx) -> {
            captured.set(ctx);
            return new RuntimeException(ctx);
        };
        mapper.on(IllegalArgumentException.class, t);

        mapper.translate(new IllegalArgumentException("boom"));

        assertEquals("boom", captured.get());
    }

    @Test
    @DisplayName("Fallback returns the same throwable when no translator matches")
    void identityFallbackWhenNoTranslator() {
        IllegalArgumentException ex = new IllegalArgumentException("orig");

        assertSame(ex, mapper.translate(ex));
    }
}
