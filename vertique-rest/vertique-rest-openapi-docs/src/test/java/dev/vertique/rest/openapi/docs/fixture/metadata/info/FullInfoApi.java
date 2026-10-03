// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

/**
 * Application {@code public} at {@code /api/public}, documented publicly, whose declaring interface
 * sets every {@code @Info} member: title, version, description, summary, terms of service, a contact
 * with name, URL and email, a license with name and SPDX identifier (no URL), and one {@code x-}
 * extension. Its contact carries no extension.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Catalog API",
                        version = "2.1",
                        description = "Items",
                        summary = "Catalog",
                        termsOfService = "https://example.test/terms",
                        contact = @Contact(name = "Ops", url = "https://example.test/ops", email = "ops@example.test"),
                        license = @License(name = "Apache 2.0", identifier = "Apache-2.0"),
                        extensions =
                                @Extension(
                                        name = "x-audience",
                                        properties = @ExtensionProperty(name = "tier", value = "public"))))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.PUBLIC_NAME,
        path = InfoRegistrations.PUBLIC_PATH,
        resources = InfoPingResource.class)
public interface FullInfoApi {}
