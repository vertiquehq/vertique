// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.micrometer.MeterRegistryBackend;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrometheusMeterRegistryProvider}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link PrometheusMeterRegistryProvider#backendName()} returns {@code "prometheus"}.</li>
 *   <li>{@link PrometheusMeterRegistryProvider#create(JsonObject)} publishes the registry to
 *       {@link PrometheusBackend} and the same instance is accessible via the backend.</li>
 *   <li>The backend registry is a {@link PrometheusMeterRegistry}.</li>
 *   <li>{@link MeterRegistryBackend#close()} clears the backend holder and is idempotent.</li>
 * </ul>
 */
class PrometheusMeterRegistryProviderTest {

    @AfterEach
    void clearBackend() {
        PrometheusBackend.clear();
    }

    // --- backendName ---

    @Nested
    @DisplayName("backendName()")
    class BackendName {

        @Test
        @DisplayName("returns 'prometheus'")
        void returnsPrometheus() {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            assertEquals("prometheus", provider.backendName());
        }
    }

    // --- create() ---

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("publishes the registry to PrometheusBackend so registry() is non-empty")
        void publishesToBackend() throws Exception {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            MeterRegistryBackend backend = provider.create(new JsonObject());

            assertTrue(PrometheusBackend.registry().isPresent(), "backend must be published after create()");
            assertSame(backend.registry(), PrometheusBackend.registry().get(), "same instance must be in backend");
        }

        @Test
        @DisplayName("registry() returns a PrometheusMeterRegistry instance")
        void registryIsPrometheusType() throws Exception {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            MeterRegistryBackend backend = provider.create(new JsonObject());

            assertInstanceOf(PrometheusMeterRegistry.class, backend.registry());
        }

        @Test
        @DisplayName("spanContext is published to PrometheusBackend alongside the registry")
        void spanContextPublished() throws Exception {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            provider.create(new JsonObject());

            assertTrue(PrometheusBackend.spanContext().isPresent(), "span context must be published after create()");
        }
    }

    // --- close() ---

    @Nested
    @DisplayName("close()")
    class Close {

        @Test
        @DisplayName("close() clears PrometheusBackend")
        void closesClearsBackend() throws Exception {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            MeterRegistryBackend backend = provider.create(new JsonObject());

            assertTrue(PrometheusBackend.registry().isPresent(), "precondition: backend present before close");

            backend.close();

            assertTrue(PrometheusBackend.registry().isEmpty(), "backend must be cleared after close()");
        }

        @Test
        @DisplayName("double-close() is idempotent and does not throw")
        void doubleCloseIsIdempotent() throws Exception {
            PrometheusMeterRegistryProvider provider = new PrometheusMeterRegistryProvider();
            MeterRegistryBackend backend = provider.create(new JsonObject());

            backend.close();
            assertDoesNotThrow(backend::close, "second close() must not throw");
        }
    }
}
