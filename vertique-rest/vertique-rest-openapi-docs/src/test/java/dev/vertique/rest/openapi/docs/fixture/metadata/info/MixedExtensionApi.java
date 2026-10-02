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

/**
 * Application {@code child} at {@code /api/child}, documented publicly, whose declaring interface
 * mixes publishable and unpublishable extensions: the {@code info} carries {@code x-audience} and
 * {@code audienceZx}, and its contact carries {@code contactZx}. Only names starting with {@code x-}
 * may appear in an Info or Contact Object; the property value {@code valueQv} of the other two must
 * never be published or echoed.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "T",
                        version = "1",
                        contact =
                                @Contact(
                                        name = "C",
                                        extensions =
                                                @Extension(
                                                        name = "contactZx",
                                                        properties =
                                                                @ExtensionProperty(name = "k", value = "valueQv"))),
                        extensions = {
                            @Extension(
                                    name = "x-audience",
                                    properties = @ExtensionProperty(name = "tier", value = "public")),
                            @Extension(
                                    name = "audienceZx",
                                    properties = @ExtensionProperty(name = "tier", value = "valueQv"))
                        }))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface MixedExtensionApi {}
