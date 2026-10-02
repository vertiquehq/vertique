// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

/** The response body of {@link StorefrontResource#getEntry}: two plain members, no rename, no hiding. */
public class EntryView {

    /** The entry's id. */
    public String id;

    /** The entry's title. */
    public String title;

    /** Creates an empty entry, for deserialization. */
    public EntryView() {}

    /**
     * Creates an entry.
     *
     * @param id    the entry's id
     * @param title the entry's title
     */
    public EntryView(String id, String title) {
        this.id = id;
        this.title = title;
    }
}
