// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

/**
 * The JSON response entity of the entry read (inferred) and of the entry creation (declared), shared
 * by both twins.
 */
public class EntryView {

    /** The entry identifier. */
    public String id;

    /** The entry title. */
    public String title;

    /** Creates an empty view. */
    public EntryView() {}

    /**
     * Creates a view.
     *
     * @param id    the entry identifier
     * @param title the entry title
     */
    public EntryView(String id, String title) {
        this.id = id;
        this.title = title;
    }
}
