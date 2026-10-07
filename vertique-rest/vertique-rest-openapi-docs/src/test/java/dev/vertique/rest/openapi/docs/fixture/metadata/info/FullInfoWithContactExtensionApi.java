// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import jakarta.annotation.security.PermitAll;

/**
 * Application {@code child} at {@code /api/child}, documented publicly, whose declaring interface
 * sets every {@code @Info} member exactly as {@link FullInfoApi} does and, in addition, gives its
 * contact the {@code x-} extension {@code x-team} with property {@code channel} = {@code ops}. It
 * shows the rendered member order of a complete {@code info}, contact extensions included.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Catalog API",
                        version = "2.1",
                        description = "Items",
                        summary = "Catalog",
                        termsOfService = "https://example.test/terms",
                        contact =
                                @Contact(
                                        name = "Ops",
                                        url = "https://example.test/ops",
                                        email = "ops@example.test",
                                        extensions =
                                                @Extension(
                                                        name = "x-team",
                                                        properties =
                                                                @ExtensionProperty(name = "channel", value = "ops"))),
                        license = @License(name = "Apache 2.0", identifier = "Apache-2.0"),
                        extensions =
                                @Extension(
                                        name = "x-audience",
                                        properties = @ExtensionProperty(name = "tier", value = "public"))))
@ApiDocs(policy = FullInfoWithContactExtensionApi.PublicDocsPolicy.class)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface FullInfoWithContactExtensionApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
