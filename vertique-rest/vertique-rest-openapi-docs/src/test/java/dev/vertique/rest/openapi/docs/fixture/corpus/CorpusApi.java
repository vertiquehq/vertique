// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code corpus} at {@code /api/corpus}: its declaring interface carries
 * {@code @ApiDocs(access = PUBLIC)}, so its document is served publicly. Its one resource, {@link
 * CorpusResource}, declares one operation per fixture of the embedding-equivalence corpus.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = CorpusApi.NAME, path = CorpusApi.PATH, resources = CorpusResource.class)
public interface CorpusApi {

    /** The application's name, which also names its document. */
    String NAME = "corpus";

    /** The application's path. */
    String PATH = "/api/corpus";

    /** The configured {@code info.title} of the document. */
    String TITLE = "Corpus";

    /** The configured {@code info.version} of the document. */
    String VERSION = "1.0";

    /** The URL of the document's JSON form. */
    String DOCUMENT_URL = "/apidocs/" + NAME + "/openapi.json";
}
