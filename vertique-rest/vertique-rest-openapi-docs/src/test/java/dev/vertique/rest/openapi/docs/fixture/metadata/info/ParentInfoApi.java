// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

/**
 * A superinterface carrying a complete, valid {@code info}, whose summary is the marker {@code
 * ParentZx}. It declares no application; {@link ChildInfoApi} and {@link OwnInfoApi} extend it. Only
 * a declaring interface's own annotation counts, so none of these members may reach a document or a
 * message.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Parent",
                        version = "7",
                        description = "From the superinterface",
                        summary = "ParentZx",
                        termsOfService = "https://example.test/parent-terms",
                        contact = @Contact(name = "ParentContact", email = "parent@example.test"),
                        license = @License(name = "ParentLicense", identifier = "MIT")))
public interface ParentInfoApi {}
