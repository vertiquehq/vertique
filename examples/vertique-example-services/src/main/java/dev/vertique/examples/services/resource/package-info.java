// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JAX-RS resources and Dagger modules for the example-services REST API.
 *
 * <p>Contains {@link dev.vertique.examples.services.resource.UserResource}, the JAX-RS
 * resource that exposes user CRUD operations via an injected event bus service client;
 * {@link dev.vertique.examples.services.resource.ResourceModule}, the Dagger module that
 * contributes resource instances to the {@code @JaxRsResources} multibinding; and
 * {@link dev.vertique.examples.services.resource.OpenApiConfig}, the OpenAPI metadata holder
 * scanned by the Swagger Maven plugin at build time.
 */
package dev.vertique.examples.services.resource;
