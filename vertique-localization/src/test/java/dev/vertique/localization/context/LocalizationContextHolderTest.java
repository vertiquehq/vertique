// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocalizationContextHolder}.
 *
 * <p>Verifies that all facade methods delegate correctly to the underlying {@link ContextHolder}
 * and that the fallback methods return the fallback value when no context is bound.
 */
class LocalizationContextHolderTest {

    private final ContextHolder holder = mock(ContextHolder.class);
    private final LocalizationContext sampleCtx = new LocalizationContext(
            Locale.forLanguageTag("fi"),
            ZoneId.of("Europe/Helsinki"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "test",
            "test");

    // --- current() ---

    @Nested
    @DisplayName("current()")
    class CurrentTests {

        @Test
        @DisplayName("delegates to holder.current(LocalizationContext.class) and returns present value")
        void delegatesAndReturnsPresentValue() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.of(sampleCtx));

            Optional<LocalizationContext> result = LocalizationContextHolder.current(holder);

            assertTrue(result.isPresent(), "expected a present value");
            assertSame(sampleCtx, result.get(), "returned value must be the same instance");
            verify(holder).current(LocalizationContext.class);
        }

        @Test
        @DisplayName("returns empty when holder has no binding")
        void returnsEmptyWhenNoBinding() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.empty());

            Optional<LocalizationContext> result = LocalizationContextHolder.current(holder);

            assertFalse(result.isPresent(), "expected empty when not bound");
        }
    }

    // --- bind() ---

    @Nested
    @DisplayName("bind()")
    class BindTests {

        @Test
        @DisplayName("delegates to holder.bind(LocalizationContext.class, ctx) and returns scope")
        void delegatesAndReturnsScope() {
            ContextHolder.Scope expectedScope = mock(ContextHolder.Scope.class);
            when(holder.bind(LocalizationContext.class, sampleCtx)).thenReturn(expectedScope);

            ContextHolder.Scope scope = LocalizationContextHolder.bind(holder, sampleCtx);

            assertSame(expectedScope, scope, "returned scope must be the same instance");
            verify(holder).bind(LocalizationContext.class, sampleCtx);
        }
    }

    // --- locale() ---

    @Nested
    @DisplayName("locale()")
    class LocaleTests {

        @Test
        @DisplayName("returns locale from current context when bound")
        void returnsLocaleFromContext() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.of(sampleCtx));
            Locale fallback = Locale.ENGLISH;

            Locale result = LocalizationContextHolder.locale(holder, fallback);

            assertEquals(sampleCtx.locale(), result, "expected locale from bound context");
        }

        @Test
        @DisplayName("returns fallback locale when no context is bound")
        void returnsFallbackLocaleWhenNotBound() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.empty());
            Locale fallback = Locale.GERMAN;

            Locale result = LocalizationContextHolder.locale(holder, fallback);

            assertSame(fallback, result, "expected fallback locale when context is absent");
        }
    }

    // --- zone() ---

    @Nested
    @DisplayName("zone()")
    class ZoneTests {

        @Test
        @DisplayName("returns zone from current context when bound")
        void returnsZoneFromContext() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.of(sampleCtx));
            ZoneId fallback = ZoneId.of("UTC");

            ZoneId result = LocalizationContextHolder.zone(holder, fallback);

            assertEquals(sampleCtx.zone(), result, "expected zone from bound context");
        }

        @Test
        @DisplayName("returns fallback zone when no context is bound")
        void returnsFallbackZoneWhenNotBound() {
            when(holder.current(LocalizationContext.class)).thenReturn(Optional.empty());
            ZoneId fallback = ZoneId.of("America/New_York");

            ZoneId result = LocalizationContextHolder.zone(holder, fallback);

            assertSame(fallback, result, "expected fallback zone when context is absent");
        }
    }
}
