// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The base of every route-collision case resource: one resource method that answers with the
 * resource's literal marker and counts the requests it answered. The count is the oracle for "the
 * request was answered by this resource"; it also works for {@code HEAD}, which carries no body.
 * The base declares no resource method of its own.
 */
public abstract class CaseResource {

    private final String marker;

    private final AtomicInteger hits = new AtomicInteger();

    /**
     * Creates a case resource.
     *
     * @param marker the literal body the resource method answers with
     */
    protected CaseResource(String marker) {
        this.marker = marker;
    }

    /**
     * Returns the literal body the resource method answers with.
     *
     * @return the marker
     */
    public String marker() {
        return marker;
    }

    /**
     * Returns the number of requests the resource method answered.
     *
     * @return the count
     */
    public int hits() {
        return hits.get();
    }

    /**
     * Counts one answered request and returns the marker.
     *
     * @return the marker
     */
    protected String answer() {
        hits.incrementAndGet();
        return marker;
    }
}
