// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.StatusResource;
import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * T023 TP-017 native application fixture: a declaring interface, never instantiated, the real
 * {@code JaxRsPipelineProcessor} turns into one {@code GeneratedRestApplicationRegistration}. Its
 * declared path {@code "/api/mgmt/*"} normalizes (C-PATH step 2 strips the terminal {@code /*}) to
 * {@code "/api/mgmt"}, mounted at {@code /api/mgmt/*} by the native composer.
 */
@RestApplication(name = "mgmt", path = "/api/mgmt/*", resources = StatusResource.class)
public interface ManagementApplication {}
