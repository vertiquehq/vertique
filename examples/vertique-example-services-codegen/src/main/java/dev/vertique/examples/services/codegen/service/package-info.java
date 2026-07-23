// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Service contracts and implementations for the example-services-codegen application.
 *
 * <p>Contains two service contracts illustrating different codegen paths:
 * <ul>
 *   <li>{@link dev.vertique.examples.services.codegen.service.BillingService} —
 *       {@code @ServiceContract} on the direct-impl path; implemented by
 *       {@link dev.vertique.examples.services.codegen.service.BillingServiceImpl}</li>
 *   <li>{@link dev.vertique.examples.services.codegen.service.ShippingService} —
 *       {@code @ServiceContract} on the handler-pattern path; handled by
 *       {@link dev.vertique.examples.services.codegen.service.ShippingServiceHandler}</li>
 * </ul>
 *
 * <p>{@code vertique-codegen-services} generates {@code BillingService_ContractContributor}
 * and {@code ShippingService_ContractContributor} in this package, plus a single
 * {@code GeneratedServicesModule} that wires both contributors into
 * the Dagger {@code @IntoSet} multibinding.
 */
package dev.vertique.examples.services.codegen.service;
