// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.response.ResponseProducerBinding;
import jakarta.ws.rs.core.Response;

/**
 * The two response producer bindings both compositions contribute: one for {@link ArchiveTicket} and
 * one for {@link ExportTicket}. Each producer answers {@code 202 Accepted} without a body; no test
 * invokes one, since only a binding's {@link ResponseProducerBinding#type() type} matters to a
 * document.
 */
public final class CompleteProducers {

    private static final ResponseProducerBinding<ArchiveTicket> ARCHIVE = new ResponseProducerBinding<>(
            ArchiveTicket.class, (ctx, result) -> Response.accepted().build());

    private static final ResponseProducerBinding<ExportTicket> EXPORT = new ResponseProducerBinding<>(
            ExportTicket.class, (ctx, result) -> Response.accepted().build());

    private CompleteProducers() {}

    /**
     * Returns the binding of {@link ArchiveTicket}; every call returns the same instance.
     *
     * @return the binding
     */
    public static ResponseProducerBinding<ArchiveTicket> archive() {
        return ARCHIVE;
    }

    /**
     * Returns the binding of {@link ExportTicket}; every call returns the same instance.
     *
     * @return the binding
     */
    public static ResponseProducerBinding<ExportTicket> export() {
        return EXPORT;
    }
}
