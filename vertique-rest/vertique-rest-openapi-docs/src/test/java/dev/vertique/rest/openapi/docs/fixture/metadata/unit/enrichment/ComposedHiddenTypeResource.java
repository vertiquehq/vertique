// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.QueryParam;

/**
 * A class hidden only through a composed annotation whose type carries {@code @Hidden}; {@link
 * PlainMarkerTypeResource} is its control. Its method's query parameter declares a name other than
 * the binding's, a check the operation would fail if it were published. It is read onto a synthetic
 * operation by {@code MetadataPublications.annotate}; nothing invokes it.
 */
@HiddenTypeZx
@SuppressWarnings("unused")
public final class ComposedHiddenTypeResource {

    private ComposedHiddenTypeResource() {}

    /**
     * Carries no annotation of its own.
     *
     * @param q the query parameter
     */
    public void search(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}
}
