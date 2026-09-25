// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * G-04 (a) fixture: an abstract {@link Application} that overrides {@link #getClasses()}, listing
 * only {@link CatalogResource}. {@link InheritingApplication} extends this class and overrides
 * nothing itself, so C-COMPOSE step 1's overriding check (a no-parameter {@code getClasses} or
 * {@code getSingletons} declared anywhere from {@code registration.type()} up to, but excluding,
 * {@code Application}) must find this declaration on the superclass, not on
 * {@link InheritingApplication} itself, and classify the registration as overriding (EXPLICIT), not
 * discovery.
 */
public abstract class AbstractListingApplication extends Application {

    /**
     * Returns {@link CatalogResource} as the sole listed member.
     *
     * @return a singleton set containing {@link CatalogResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(CatalogResource.class);
    }
}
