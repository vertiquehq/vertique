// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-008's resource for {@link UnitTwoApi} and {@link UnitTwoRenamedApi}, counting its
 * constructions so the test can assert no catalog entry or manual contribution is constructed
 * before a duplicate-name failure.
 */
@Path("/dupname/two")
public class UnitTwoResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public UnitTwoResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /dupname/two}.
     *
     * @return the fixed body {@code "two"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String two() {
        return "two";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
