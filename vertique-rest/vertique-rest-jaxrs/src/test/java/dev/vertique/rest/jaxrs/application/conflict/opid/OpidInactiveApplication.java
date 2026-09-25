// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (e) fixture: an application registered by
 * {@link OpidInactiveApplicationModule}, whose condition never matches (AC-014.1: "no active
 * application"). Never constructed in this case; {@link #getClasses()} exists only to satisfy
 * {@link Application}'s contract and is never invoked.
 */
@ApplicationPath("/opid/inactive")
public class OpidInactiveApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/inactive";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidInactiveApplication() {}

    /**
     * Returns an empty set; never invoked, since this application's registration is inactive.
     *
     * @return an empty set
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of();
    }
}
