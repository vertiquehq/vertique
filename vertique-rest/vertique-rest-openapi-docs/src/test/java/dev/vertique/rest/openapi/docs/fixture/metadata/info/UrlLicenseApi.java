// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

/**
 * Application {@code child} at {@code /api/child}, documented publicly, whose declaring interface
 * sets title {@code T}, version {@code 1}, and a license given by name {@code MIT} and URL, with no
 * identifier.
 */
@OpenAPIDefinition(
        info = @Info(title = "T", version = "1", license = @License(name = "MIT", url = "https://example.test/mit")))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface UrlLicenseApi {}
