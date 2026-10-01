// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The one resource every {@code info} fixture application lists, because a registration must list at
 * least one resource: {@code GET /ping}, which answers {@code 204 No Content}. It carries no
 * documentation annotation, so the documents of these applications differ only in their {@code
 * info}.
 */
@Path(InfoPingResource.PATH)
public class InfoPingResource {

    /** The resource path. */
    public static final String PATH = "/ping";

    /** The operation id of {@link #ping}. */
    public static final String PING = "ping";

    /** Public {@code @Inject} constructor. */
    @Inject
    public InfoPingResource() {}

    /** Handles {@code GET /ping} and returns no content. */
    @GET
    public void ping() {}
}
