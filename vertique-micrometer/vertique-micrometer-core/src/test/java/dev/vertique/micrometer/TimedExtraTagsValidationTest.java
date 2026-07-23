// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.core.codegen.MethodMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FR (Bug F6) even-length validation of {@link Timed#extraTags()} in
 * {@link TimedAspect#interceptor}.
 *
 * <p>The {@code extraTags} are hoisted into {@code interceptor(...)} (proxy-construction time), so a
 * misconfigured odd-length {@code extraTags()} MUST fail fast there with an actionable
 * {@link IllegalArgumentException} naming {@code @Timed.extraTags()} — rather than being silently
 * dropped later at record time (Micrometer's {@code Tags.of} rejects an odd-length varargs).
 */
class TimedExtraTagsValidationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(registry, Optional.empty());
    private final MethodMetadata target = TimedFixtures.method("op", TimedExtraTagsValidationTest.class, void.class);

    @Test
    @DisplayName("odd-length extraTags throws IllegalArgumentException naming @Timed.extraTags() at construction")
    void oddLengthExtraTagsThrowsAtConstruction() {
        Timed odd = TimedFixtures.literal("myOp", "region");

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> provider.interceptor(target, odd));

        assertNotNull(ex.getMessage());
        assertTrue(
                ex.getMessage().contains("@Timed.extraTags()"),
                "message names the offending attribute: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains("myOp"),
                "message names the metric so the misconfiguration is locatable: " + ex.getMessage());
    }

    @Test
    @DisplayName("even-length extraTags builds the interceptor without throwing")
    void evenLengthExtraTagsBuildsInterceptor() {
        Timed even = TimedFixtures.literal("myOp", "region", "eu");

        assertDoesNotThrow(() -> {
            assertNotNull(provider.interceptor(target, even));
        });
    }
}
