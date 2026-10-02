// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

/**
 * The result of the export, shared by both twins. A response producer is bound to it ({@link
 * CompleteProducers#export()}), so its response is decided at runtime and no document describes its
 * body.
 */
public class ExportTicket {

    /** A member a JSON encoder would otherwise serialize. */
    public String location;

    /** Creates an empty ticket. */
    public ExportTicket() {}
}
