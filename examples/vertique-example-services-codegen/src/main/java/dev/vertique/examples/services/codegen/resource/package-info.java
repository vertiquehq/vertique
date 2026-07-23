// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * REST resources for the example-services-codegen application.
 *
 * <p>Exposes the service contracts over HTTP for integration testing:
 * <ul>
 *   <li>{@link dev.vertique.examples.services.codegen.resource.BillingResource} —
 *       {@code POST /billing/charge}</li>
 *   <li>{@link dev.vertique.examples.services.codegen.resource.ShippingResource} —
 *       {@code POST /shipping/ship}, {@code POST /shipping/notify}</li>
 * </ul>
 *
 * <p>Each resource injects the corresponding service contract proxy provided by
 * {@link dev.vertique.examples.services.codegen.ServiceModule}.
 * {@code AutoWireProcessor} generates {@code GeneratedJaxRsResourcesModule} in this package
 * at compile time to wire the resources into the {@code @JaxRsResources} multibinding.
 */
package dev.vertique.examples.services.codegen.resource;
