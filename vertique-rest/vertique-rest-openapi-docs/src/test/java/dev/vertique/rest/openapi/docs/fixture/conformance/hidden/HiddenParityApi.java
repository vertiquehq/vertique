// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.HiddenInterfaceResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.HiddenTypeResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.MixedMethodsResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.PartlyHiddenInterfaceResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing one resource of every hiding
 * placement, all from the package {@code fixture.conformance.hidden.scan}, which holds nothing else:
 * {@link MixedMethodsResource} (methods hidden by {@code @Hidden}, a method hidden by
 * {@code @Operation(hidden = true)}, and a visible method), {@link HiddenTypeResource} (the class
 * hidden by {@code @Hidden}), {@link HiddenInterfaceResource} (implementing an interface hidden by
 * {@code @Hidden}), and {@link PartlyHiddenInterfaceResource} (implementing an interface with one
 * method hidden by {@code @Hidden} and one visible method). Its document is public and generated, with the
 * {@code info} this interface carries. This interface stays outside that package, so a scan of the
 * package never finds its {@code @OpenAPIDefinition}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(info = @Info(title = HiddenParityApi.TITLE, version = HiddenParityApi.VERSION))
@RestApplication(
        name = HiddenParityApi.NAME,
        path = HiddenParityApi.PATH,
        resources = {
            MixedMethodsResource.class,
            HiddenTypeResource.class,
            HiddenInterfaceResource.class,
            PartlyHiddenInterfaceResource.class
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
