// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource;

/**
 * TP-001's explicit-mode application fixture, registered by
 * {@link GeneratedJaxRsResourcesModule#publicApplicationRegistration}: listing
 * {@link ScopedResource}, {@link CatalogResource}, and the manual {@link BlobLikeResource}. Never
 * implemented: native composition reads {@link #resources()} from the registration directly and
 * never constructs the declaring interface.
 */
@RestApplication(
        name = "public",
        path = "/api/public",
        resources = {ScopedResource.class, CatalogResource.class, BlobLikeResource.class})
public interface PublicApi {}
