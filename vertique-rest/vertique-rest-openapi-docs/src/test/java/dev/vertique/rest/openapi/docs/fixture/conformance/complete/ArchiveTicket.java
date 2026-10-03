// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

/**
 * The result of the entry archive, shared by both twins. A response producer is bound to it ({@link
 * CompleteProducers#archive()}), so its response is decided at runtime and no document describes its
 * body.
 */
public class ArchiveTicket {

    /** A member a JSON encoder would otherwise serialize. */
    public String ticket;

    /** Creates an empty ticket. */
    public ArchiveTicket() {}
}
