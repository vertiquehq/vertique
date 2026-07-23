// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.bootstrap.DefaultBootstrapContext;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the defensive-copy and live-reference semantics of {@link DefaultBootstrapContext}.
 */
class DefaultBootstrapContextTest {

    // --- config() ---

    @Nested
    @DisplayName("config()")
    class ConfigTests {

        @Test
        @DisplayName("config() never returns null")
        void configNeverNull() {
            DefaultBootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            assertNotNull(ctx.config());
        }

        @Test
        @DisplayName(
                "config() returns a defensive copy — mutating the returned object does not affect subsequent calls")
        void configReturnsDefensiveCopy() {
            JsonObject original = new JsonObject().put("key", "value");
            DefaultBootstrapContext ctx = new DefaultBootstrapContext(original, new VertxOptions());

            JsonObject first = ctx.config();
            first.put("extra", "injected");

            JsonObject second = ctx.config();
            assertEquals(false, second.containsKey("extra"), "mutation of returned copy must not affect the original");
            assertEquals("value", second.getString("key"), "original key must still be present");
        }

        @Test
        @DisplayName("config() reflects original values from construction time")
        void configReflectsOriginalValues() {
            JsonObject cfg = new JsonObject().put("a", 1).put("b", "hello");
            DefaultBootstrapContext ctx = new DefaultBootstrapContext(cfg, new VertxOptions());

            JsonObject result = ctx.config();
            assertEquals(1, result.getInteger("a"));
            assertEquals("hello", result.getString("b"));
        }
    }

    // --- vertxOptions() ---

    @Nested
    @DisplayName("vertxOptions()")
    class VertxOptionsTests {

        @Test
        @DisplayName("vertxOptions() returns the same live instance — mutations are observable")
        void vertxOptionsReturnsSameLiveInstance() {
            VertxOptions options = new VertxOptions();
            DefaultBootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), options);

            assertSame(options, ctx.vertxOptions(), "must return the exact same instance passed at construction");
        }

        @Test
        @DisplayName("mutations to vertxOptions() are immediately visible on the next call")
        void mutationsAreVisible() {
            VertxOptions options = new VertxOptions();
            DefaultBootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), options);

            ctx.vertxOptions().setWorkerPoolSize(42);

            assertEquals(42, ctx.vertxOptions().getWorkerPoolSize(), "mutation must be immediately observable");
        }
    }

    // --- Constructor validation ---

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("null config throws NullPointerException")
        void nullConfigThrows() {
            assertThrows(NullPointerException.class, () -> new DefaultBootstrapContext(null, new VertxOptions()));
        }

        @Test
        @DisplayName("null vertxOptions throws NullPointerException")
        void nullVertxOptionsThrows() {
            assertThrows(NullPointerException.class, () -> new DefaultBootstrapContext(new JsonObject(), null));
        }
    }
}
