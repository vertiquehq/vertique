// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.CatalogResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * T003 TP-005 explicit-mode application fixture: an eligible {@code Application} subtype the real
 * {@code JaxRsApplicationScanner} registers through the {@code A::new} C-GEN factory shape (a public
 * no-arg constructor). Its declared path {@code "/api/public/"} normalizes (C-PATH steps 1 and 2) to
 * {@code "/api/public"}, mounted at {@code /api/public/*} by {@code JaxRsApplicationComposer}.
 */
@ApplicationPath("/api/public/")
public class PublicApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public PublicApplication() {}

    /**
     * Selects only {@link CatalogResource}.
     *
     * @return a singleton set containing {@link CatalogResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(CatalogResource.class);
    }
}
