// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import dev.vertique.sanitization.SanitizationModule;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Combined REST + WebSocket Dagger wiring tests proving that both transports' independent {@code
 * @BindsOptionalOf} declarations for the neutral {@link InputObjectProcessor} coexist in one
 * component (duplicate optional declarations of the same key are legal) and are satisfied by a
 * single shared {@code SanitizationModule} singleton (AC-INP-010 combined; AC-INP-042/043/044):
 * with the module installed the optional resolves present and both transports see the same
 * instance; without it, empty.
 */
class CombinedTransportSanitizationComponentTest {

    /** Component with both transports and {@code SanitizationModule} — the optional is satisfied. */
    @Singleton
    @Component(modules = {RestModule.class, WebSocketModule.class, SanitizationModule.class})
    interface WithSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    /** Component with both transports but no {@code SanitizationModule} — the optional stays empty. */
    @Singleton
    @Component(modules = {RestModule.class, WebSocketModule.class})
    interface WithoutSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    @Test
    @DisplayName("SanitizationModule satisfies the combined REST+WebSocket optional InputObjectProcessor binding")
    void sanitizationModulePresentResolvesProcessor() {
        WithSanitizationComponent component =
                DaggerCombinedTransportSanitizationComponentTest_WithSanitizationComponent.create();

        assertTrue(component.inputObjectProcessor().isPresent());
    }

    @Test
    @DisplayName("Both transports resolve the same singleton InputObjectProcessor instance")
    void processorIsTheSameSingletonInstanceAcrossResolutions() {
        WithSanitizationComponent component =
                DaggerCombinedTransportSanitizationComponentTest_WithSanitizationComponent.create();

        InputObjectProcessor first = component.inputObjectProcessor().orElseThrow();
        InputObjectProcessor second = component.inputObjectProcessor().orElseThrow();

        assertSame(first, second);
    }

    @Test
    @DisplayName("Without SanitizationModule the combined optional InputObjectProcessor binding is empty")
    void withoutSanitizationModuleOptionalIsEmpty() {
        WithoutSanitizationComponent component =
                DaggerCombinedTransportSanitizationComponentTest_WithoutSanitizationComponent.create();

        assertFalse(component.inputObjectProcessor().isPresent());
    }
}
