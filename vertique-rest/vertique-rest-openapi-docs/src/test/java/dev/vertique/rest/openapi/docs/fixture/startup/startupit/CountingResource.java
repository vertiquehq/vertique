// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The base of every startup-check resource: its one resource method answers with the resource's
 * literal marker and counts the requests it answered. The count is the oracle for "the request was
 * answered by this resource". The base declares no resource method of its own.
 */
public abstract class CountingResource {

    private final String marker;

    private final AtomicInteger hits = new AtomicInteger();

    /**
     * Creates a counting resource.
     *
     * @param marker the literal body the resource method answers with
     */
    protected CountingResource(String marker) {
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
