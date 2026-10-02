// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenClassResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenContractResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.MixedOperationsResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.PartlyHiddenContractResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing one resource of every hiding
 * placement: {@link MixedOperationsResource} (a method hidden by {@code @Hidden}, a method hidden by
 * {@code @Operation(hidden = true)}, and a visible method), {@link HiddenClassResource} (the class
 * hidden by {@code @Hidden}), {@link HiddenContractResource} (implementing an interface hidden by
 * {@code @Hidden}), and {@link PartlyHiddenContractResource} (implementing an interface with one
 * method hidden by {@code @Hidden} and one visible method). Its document is public and generated,
 * with the {@code info} this interface carries.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = HiddenParityApi.TITLE, version = HiddenParityApi.VERSION))
@RestApplication(
        name = HiddenParityApi.NAME,
        path = HiddenParityApi.PATH,
        resources = {
            MixedOperationsResource.class,
            HiddenClassResource.class,
            HiddenContractResource.class,
            PartlyHiddenContractResource.class
        })
public interface HiddenParityApi {

    /** The application's name, which also names its document. */
    String NAME = "hiddenparity";

    /** The application's path. */
    String PATH = "/api/hiddenparity";

    /** The annotated {@code info.title}. */
    String TITLE = "Hidden parity";

    /** The annotated {@code info.version}. */
    String VERSION = "1.0";
}
