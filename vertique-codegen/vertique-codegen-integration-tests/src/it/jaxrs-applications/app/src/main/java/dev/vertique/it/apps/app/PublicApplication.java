// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dev.vertique.it.apps.resources.CatalogResource;
import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * Native application fixture: a declaring interface, never instantiated, the real
 * {@code JaxRsPipelineProcessor} turns into one {@code GeneratedRestApplicationRegistration}. Its
 * declared path {@code "/api/public/"} normalizes (path normalization strips the trailing slash) to {@code "/api/public"},
 * mounted at {@code /api/public/*} by the native composer.
 */
@RestApplication(name = "public", path = "/api/public/", resources = CatalogResource.class)
public interface PublicApplication {}
