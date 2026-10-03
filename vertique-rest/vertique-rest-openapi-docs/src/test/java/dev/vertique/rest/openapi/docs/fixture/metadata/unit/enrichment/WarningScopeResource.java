// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.QueryParam;

/**
 * Fixture methods for the scope of warnings: several warned inputs in one operation, and an
 * operation that fails beside one that warns. They are read onto synthetic operations by {@code
 * MetadataPublications.annotate}; nothing invokes them. Each method lists exactly one parameter per
 * binding, in builder order.
 *
 * <p>A plain {@code String} query parameter is not required; a primitive {@code int} query
 * parameter's requiredness is unknown. String members carry the sentinel {@code VALUEZX}. The class
 * itself carries no annotation.
 */
@SuppressWarnings("unused")
public final class WarningScopeResource {

    private WarningScopeResource() {}

    /**
     * Requirements on two query parameters whose requiredness the runtime leaves unknown.
     *
     * @param q the first query parameter
     * @param r the second query parameter
     */
    public void twoUnknownRequirements(
            @Parameter(required = true) @QueryParam("q") int q, @Parameter(required = true) @QueryParam("r") int r) {}

    /**
     * A parameter name other than the binding's.
     *
     * @param q the query parameter
     */
    public void otherParameterName(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}
}
