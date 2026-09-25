// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.ws.rs.ApplicationPath;

/**
 * G-04 (a) fixture: a concrete {@link AbstractListingApplication} subclass that overrides nothing
 * itself. Its registration must still classify as overriding (EXPLICIT), because
 * {@link AbstractListingApplication} declares {@code getClasses()} between this class and
 * {@code Application} — C-COMPOSE step 1's overriding walk must find it there.
 */
@ApplicationPath("/api/inheriting")
public class InheritingApplication extends AbstractListingApplication {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public InheritingApplication() {}
}
