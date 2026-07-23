// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vertique.core.json.JacksonConfigurer;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link JacksonConfigureStep} — the framework's {@link LifecyclePhase#CONFIGURE}
 * lifecycle step that delegates Jackson configuration to {@link JacksonConfigurer}.
 */
class JacksonConfigureStepTest {

    @Test
    @DisplayName("phase() is CONFIGURE")
    void phaseIsConfigure() {
        JacksonConfigureStep step = new JacksonConfigureStep(Mockito.mock(JacksonConfigurer.class));

        assertEquals(LifecyclePhase.CONFIGURE, step.phase());
    }

    @Test
    @DisplayName("start() invokes JacksonConfigurer.configure() and returns a succeeded future")
    void startInvokesConfigureAndSucceeds() {
        JacksonConfigurer configurer = Mockito.mock(JacksonConfigurer.class);
        JacksonConfigureStep step = new JacksonConfigureStep(configurer);

        Future<Void> result = step.start();

        verify(configurer, times(1)).configure();
        assertTrue(result.succeeded(), "start() must return a succeeded future");
    }
}
