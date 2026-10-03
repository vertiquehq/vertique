// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The single REST application of the example-services-codegen application.
 *
 * <p>One application mounted at the root serves every discovered resource at the paths the default
 * mount served. {@link ApiDocs} publishes its OpenAPI document at
 * {@code /apidocs/services/openapi.json} and {@code .yaml} unless configuration disables it.
 */
@RestApplication(name = "services", path = "/", discover = true)
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Example Services Codegen API",
                        version = "0.1.0",
                        description =
                                "Demonstrates compile-time service contract codegen with direct-impl and handler-pattern contracts"))
public interface ServicesApi {}
