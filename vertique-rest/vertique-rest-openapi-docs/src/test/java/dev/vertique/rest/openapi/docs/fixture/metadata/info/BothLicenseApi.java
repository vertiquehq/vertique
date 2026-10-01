// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

/**
 * Application {@code child} at {@code /api/child}, documented publicly, whose declaring interface
 * sets a license with both an identifier ({@code IdZx}) and a URL (containing {@code urlzx}), which
 * an OpenAPI License Object may not carry together. Neither value may be echoed in a message.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "T",
                        version = "1",
                        license = @License(name = "L", identifier = "IdZx", url = "https://example.test/urlzx")))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface BothLicenseApi {}
