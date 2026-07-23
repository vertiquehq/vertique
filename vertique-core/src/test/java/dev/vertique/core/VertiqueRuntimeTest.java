// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertiqueRuntime}: the neutral {@code {Vertx, JsonObject}} graph-input
 * carrier. Verifies the {@link VertiqueRuntime#of(Vertx, JsonObject) of} factory exposes the exact
 * inputs it was handed and that the compact constructor rejects {@code null} components.
 *
 * <p>A Mockito mock {@link Vertx} is used rather than a real {@code Vertx.vertx()} so the test needs
 * no event-loop setup or teardown — {@code VertiqueRuntime} only stores and returns the reference.
 */
class VertiqueRuntimeTest {

    @Test
    @DisplayName("of() exposes the same vertx and config references it was given")
    void of_exposesSameVertxAndConfig() {
        Vertx vertx = mock(Vertx.class);
        JsonObject config = new JsonObject().put("http.port", 8080);

        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, config);

        assertSame(vertx, runtime.vertx());
        assertSame(config, runtime.config());
    }

    @Test
    @DisplayName("of() rejects a null vertx with NullPointerException")
    void of_nullVertx_throwsNpe() {
        JsonObject config = new JsonObject();

        assertThrows(NullPointerException.class, () -> VertiqueRuntime.of(null, config));
    }

    @Test
    @DisplayName("of() rejects a null config with NullPointerException")
    void of_nullConfig_throwsNpe() {
        Vertx vertx = mock(Vertx.class);

        assertThrows(NullPointerException.class, () -> VertiqueRuntime.of(vertx, null));
    }
}
