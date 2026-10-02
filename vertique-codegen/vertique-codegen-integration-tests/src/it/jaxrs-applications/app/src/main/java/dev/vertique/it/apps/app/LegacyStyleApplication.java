// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.CatalogResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * Leftover-adapter fixture: a concrete {@code jakarta.ws.rs.core.Application}
 * subclass the native composer never registers and never instantiates. The compile-time allow list
 * reports exactly one warning naming this class; at runtime it and every resource it lists
 * ({@link CatalogResource}) stay inert, so {@code GET /api/legacy/catalog} must 404 rather than
 * serve.
 */
@ApplicationPath("/api/legacy")
public class LegacyStyleApplication extends Application {

    /** Public no-arg constructor; irrelevant at runtime since this subclass is never instantiated. */
    public LegacyStyleApplication() {}

    /**
     * Selects only {@link CatalogResource}, irrelevant at runtime since this subclass is never
     * registered.
     *
     * @return a singleton set containing {@link CatalogResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(CatalogResource.class);
    }
}
