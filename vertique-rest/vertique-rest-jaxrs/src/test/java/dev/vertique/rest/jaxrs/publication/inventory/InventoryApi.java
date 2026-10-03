// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.application.RestApplication;

/**
 * Declaring type of the {@code inventory} application over the reflection-path inventory
 * resources. Never implemented and read by no production code here: a test builds this
 * application's mount directly through the package-private application-mount factory method.
 */
@RestApplication(
        name = "inventory",
        path = "/api/inventory",
        resources = {OrdersResource.class, SequencedResource.class, KindsResource.class})
public interface InventoryApi {}
