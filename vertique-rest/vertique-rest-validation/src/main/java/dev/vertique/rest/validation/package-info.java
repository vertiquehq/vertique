// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Default annotation-driven request validation strategy for the Vertique REST framework.
 *
 * <p>This module hosts the {@code web-validation} gate — the framework's default request-validation
 * strategy, built on annotation-synthesized JSON Schemas. It carries no preview OpenAPI-router
 * artifact; the opt-in {@code openapi-contract} strategy lives in
 * {@code vertique-rest-openapi-validation} instead.
 *
 * <p>The {@code web-validation} gate is implemented here: {@link dev.vertique.rest.validation.WebValidationStrategy}
 * synthesizes JSON Schemas from annotations via {@link dev.vertique.rest.validation.AnnotationSchemaSource}, and
 * {@link dev.vertique.rest.validation.RestValidationModule} is the Dagger entry point that wires both into the
 * {@code Set<RequestValidationStrategy>} and {@code Set<OperationSchemaSource>} multibindings.
 */
package dev.vertique.rest.validation;
