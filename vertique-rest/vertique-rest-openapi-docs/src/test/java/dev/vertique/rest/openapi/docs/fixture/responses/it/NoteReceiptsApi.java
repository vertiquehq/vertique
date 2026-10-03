// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code notes} at {@code /api} (mount {@code /api/*}), listing {@link
 * NoteReceiptResource}. Its one operation returns {@code Future<NoteReceiptZx>}, which reaches a
 * type annotated {@code @Schema(hidden = true)}. It shares the name {@code notes} with {@link
 * NotesApi}; no component lists both. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = NoteReceiptsApi.NAME, path = NoteReceiptsApi.PATH, resources = NoteReceiptResource.class)
public interface NoteReceiptsApi {

    /** The application's name, which also names its document. */
    String NAME = "notes";

    /** The application's path. */
    String PATH = "/api";
}
