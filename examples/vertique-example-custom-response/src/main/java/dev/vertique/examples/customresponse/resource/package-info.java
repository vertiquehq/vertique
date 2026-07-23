// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JAX-RS resource classes and supporting types for the example-custom-response application.
 * Includes {@code ItemResource}, a JAX-RS resource with CRUD endpoints for an in-memory item
 * store that demonstrates SHA-256 digest headers on responses, request body digest
 * verification, categorized error responses, and JWT + RBAC authorization; and
 * {@code ResourceModule}, the Dagger module that contributes all resource instances to the
 * {@code @JaxRsResources} multibinding, the custom exception mapper, and the digest request
 * hook so they are discovered and registered at startup.
 */
package dev.vertique.examples.customresponse.resource;
