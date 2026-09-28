// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;

/**
 * TP-001's second explicit-mode application fixture, registered by
 * {@link GeneratedJaxRsResourcesModule#managementApplicationRegistration}: listing
 * {@link ExtraResource} as its sole resource. Never implemented: native composition reads
 * {@link #resources()} from the registration directly and never constructs the declaring
 * interface.
 */
@RestApplication(name = "mgmt", path = "/api/mgmt", resources = ExtraResource.class)
public interface ManagementApi {}
