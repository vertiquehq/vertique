// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.served;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.contract.CatalogEntryResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link CatalogEntryResource}.
 * It names no contract of its own, so its document is generated, with the {@code info} its declaring
 * interface carries.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = ConformanceCatalogApi.TITLE, version = ConformanceCatalogApi.VERSION))
@RestApplication(
        name = ConformanceCatalogApi.NAME,
        path = ConformanceCatalogApi.PATH,
        resources = CatalogEntryResource.class)
public interface ConformanceCatalogApi {

    /** The application's name, which also names its document. */
    String NAME = "catalog";

    /** The application's path. */
    String PATH = "/api/catalog";

    /** The application's mount path, as the document store's log lines name it. */
    String MOUNT_PATH = PATH + "/*";

    /** The annotated {@code info.title}. */
    String TITLE = "Conformance catalog";

    /** The annotated {@code info.version}. */
    String VERSION = "1.0";
}
