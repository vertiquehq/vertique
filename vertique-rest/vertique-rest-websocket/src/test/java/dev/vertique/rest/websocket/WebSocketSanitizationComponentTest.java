// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import dev.vertique.sanitization.SanitizationModule;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger wiring tests proving that {@link WebSocketModule}'s {@code @BindsOptionalOf} declaration
 * for the neutral {@link InputObjectProcessor} is satisfied by {@code SanitizationModule}: with the
 * module installed the optional resolves present; without it, empty.
 */
class WebSocketSanitizationComponentTest {

    /** Component with {@code SanitizationModule} installed — the optional binding is satisfied. */
    @Singleton
    @Component(modules = {WebSocketModule.class, SanitizationModule.class})
    interface WithSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    /** Component without {@code SanitizationModule} — the optional binding stays empty. */
    @Singleton
    @Component(modules = WebSocketModule.class)
    interface WithoutSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    @Test
    @DisplayName("SanitizationModule satisfies WebSocketModule's optional InputObjectProcessor binding")
    void sanitizationModulePresentResolvesProcessor() {
        WithSanitizationComponent component =
                DaggerWebSocketSanitizationComponentTest_WithSanitizationComponent.create();

        assertTrue(component.inputObjectProcessor().isPresent());
    }

    @Test
    @DisplayName("Without SanitizationModule the optional InputObjectProcessor binding is empty")
    void withoutSanitizationModuleOptionalIsEmpty() {
        WithoutSanitizationComponent component =
                DaggerWebSocketSanitizationComponentTest_WithoutSanitizationComponent.create();

        assertFalse(component.inputObjectProcessor().isPresent());
    }
}
