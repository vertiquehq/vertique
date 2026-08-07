// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.SecurityModule;
import dev.vertique.rest.security.VertxAuthorizationImportModule;
import dev.vertique.rest.security.VertxAuthorizationImporter;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph-compilation proof for the WebSocket parity of the Vert.x authorization import.
 *
 * <p>Declares a component combining {@link WebSocketModule} with the opt-in
 * {@link VertxAuthorizationImportModule} (plus {@link AuthModule}, which declares the
 * {@code @BindsOptionalOf VertxAuthorizationImporter} seam, and {@link SecurityModule}) and proves
 * two things at once:
 *
 * <ol>
 *   <li><strong>Compile-time:</strong> the four modules coexist in one component without duplicate
 *       bindings on the importer path — the {@code Set<AuthorizationProvider>} multibinding
 *       declarations coalesce and the {@code @Provides VertxAuthorizationImporter} satisfies
 *       {@code AuthModule}'s optional seam. A wiring conflict fails this test class's compilation,
 *       not its execution.</li>
 *   <li><strong>Runtime:</strong> the component resolves {@code Optional<VertxAuthorizationImporter>}
 *       as present with a non-null importer.</li>
 * </ol>
 */
class WebSocketImportComponentWiringTest {

    /**
     * Test component combining the WebSocket module with the opt-in Vert.x authorization import
     * module. Exposes only the optional importer seam — the single binding this wiring proof
     * requests from the graph.
     */
    @Singleton
    @Component(
            modules = {
                WebSocketModule.class,
                VertxAuthorizationImportModule.class,
                AuthModule.class,
                SecurityModule.class
            })
    interface WiringComponent {

        /**
         * Returns the optional Vert.x authorization importer resolved by the graph.
         *
         * @return the optional importer; present because {@link VertxAuthorizationImportModule} is
         *         in the component
         */
        Optional<VertxAuthorizationImporter> vertxAuthorizationImporter();
    }

    @Test
    @DisplayName("component with WebSocketModule + VertxAuthorizationImportModule resolves a present importer")
    void componentWithBothModulesResolvesImporterPresent() {
        Optional<VertxAuthorizationImporter> importer =
                DaggerWebSocketImportComponentWiringTest_WiringComponent.create()
                        .vertxAuthorizationImporter();

        assertTrue(
                importer.isPresent(),
                "Optional<VertxAuthorizationImporter> must be present when VertxAuthorizationImportModule is wired");
        assertNotNull(importer.get(), "the resolved importer must be non-null");
    }
}
