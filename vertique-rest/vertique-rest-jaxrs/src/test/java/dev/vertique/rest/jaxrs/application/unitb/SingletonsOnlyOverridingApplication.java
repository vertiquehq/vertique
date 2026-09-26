// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * G-04 (b) fixture: overrides only {@link #getSingletons()} (returning an empty set), never
 * {@link #getClasses()}. C-COMPOSE step 1's overriding check must classify this registration as
 * overriding purely because {@link #getSingletons()} is declared, not {@code getClasses()} — so its
 * inherited, empty {@code getClasses()} must fail as "empty getClasses()", never fall back to
 * discovery membership.
 */
@ApplicationPath("/api/singletons-only")
public class SingletonsOnlyOverridingApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public SingletonsOnlyOverridingApplication() {}

    /**
     * Returns an empty set, declared purely so C-COMPOSE step 1 classifies this registration as
     * overriding.
     *
     * @return an empty set
     */
    @Override
    public Set<Object> getSingletons() {
        return Set.of();
    }
}
