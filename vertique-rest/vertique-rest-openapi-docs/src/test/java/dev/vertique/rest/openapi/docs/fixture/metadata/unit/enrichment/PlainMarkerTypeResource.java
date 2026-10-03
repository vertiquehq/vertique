// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.QueryParam;

/**
 * The control of {@link ComposedHiddenTypeResource}: the same method on a class carrying an annotation
 * whose type carries no documentation annotation, so nothing hides it. It is read onto a synthetic
 * operation by {@code MetadataPublications.annotate}; nothing invokes it.
 */
@PlainMarkerZx
@SuppressWarnings("unused")
public final class PlainMarkerTypeResource {

    private PlainMarkerTypeResource() {}

    /**
     * Carries no annotation of its own.
     *
     * @param q the query parameter
     */
    public void search(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}
}
