// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Opt-in OpenAPI-contract request validation strategy for the Vertique REST framework.
 *
 * <p>This module isolates the {@code openapi-contract} validation strategy so the default
 * {@code web-validation} path (in {@code vertique-rest-validation}) carries no preview
 * OpenAPI-router artifact. Applications opt in by installing
 * {@link dev.vertique.rest.openapi.validation.OpenApiContractValidationModule}.
 *
 * <p>The {@code openapi-contract} strategy is implemented here:
 * {@link dev.vertique.rest.openapi.validation.OpenApiContractValidationStrategy} loads
 * {@code openapi.json} from the classpath via the Vert.x {@code OpenAPIContract} API and installs
 * contract-backed validation handlers per route, wired by
 * {@link dev.vertique.rest.openapi.validation.OpenApiContractValidationModule}.
 */
package dev.vertique.rest.openapi.validation;
