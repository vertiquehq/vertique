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
 * {@link dev.vertique.rest.validation.RestValidationModule} is the Dagger entry point that contributes the
 * strategy into the {@code Set<RequestValidationStrategy>} multibinding and binds the schema source to the
 * single optional {@code OperationSchemaSource} binding {@code RestModule} declares with
 * {@code @BindsOptionalOf}. There is no {@code Set<OperationSchemaSource>}: an application supplying its own
 * source binds it plainly, with {@code @Provides} or {@code @Binds}, and leaves this module's binding out of
 * the component rather than adding a second one.
 */
package dev.vertique.rest.validation;
