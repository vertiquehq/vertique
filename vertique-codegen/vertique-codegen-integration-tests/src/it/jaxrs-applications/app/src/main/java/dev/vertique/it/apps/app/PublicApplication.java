// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.CatalogResource;
import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * T023 TP-017 native application fixture: a declaring interface, never instantiated, the real
 * {@code JaxRsPipelineProcessor} turns into one {@code GeneratedRestApplicationRegistration}. Its
 * declared path {@code "/api/public/"} normalizes (C-PATH steps 1 and 2) to {@code "/api/public"},
 * mounted at {@code /api/public/*} by the native composer.
 */
@RestApplication(name = "public", path = "/api/public/", resources = CatalogResource.class)
public interface PublicApplication {}
