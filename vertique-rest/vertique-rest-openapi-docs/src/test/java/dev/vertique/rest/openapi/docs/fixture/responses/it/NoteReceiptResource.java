// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.NoteReceiptZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.NoteZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link NoteReceiptsApi}: {@code GET /note-receipts} (operation
 * {@value #OPERATION_ID}, the method name). It declares no response.
 */
@Path(NoteReceiptResource.ROUTE)
public class NoteReceiptResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/note-receipts";

    /** The operation id of {@link #readNoteZx}. */
    public static final String OPERATION_ID = "readNoteZx";

    /** Creates the resource. */
    public NoteReceiptResource() {}

    /**
     * Handles {@code GET /note-receipts}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<NoteReceiptZx> readNoteZx() {
        return Future.succeededFuture(new NoteReceiptZx(new NoteZx("hello")));
    }
}
