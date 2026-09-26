// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.StatusResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * T003 TP-005 explicit-mode application fixture: an eligible {@code Application} subtype the real
 * {@code JaxRsApplicationScanner} registers through the {@code A::new} C-GEN factory shape (a public
 * no-arg constructor). Its declared path {@code "/api/mgmt/*"} normalizes (C-PATH step 2 strips the
 * terminal {@code /*}) to {@code "/api/mgmt"}, mounted at {@code /api/mgmt/*} by
 * {@code JaxRsApplicationComposer}.
 */
@ApplicationPath("/api/mgmt/*")
public class ManagementApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public ManagementApplication() {}

    /**
     * Selects only {@link StatusResource}.
     *
     * @return a singleton set containing {@link StatusResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(StatusResource.class);
    }
}
