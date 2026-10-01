// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.QueryParam;

/**
 * Twin fixture methods for operations hidden only through a composed annotation: each {@code
 * ...Hidden} method carries an annotation whose type carries the hidden marker, and its {@code
 * ...Visible} twin carries a composed annotation that does not hide. Every method's query parameter
 * declares a name other than the binding's, a check the operation would fail if it were published.
 * They are read onto synthetic operations by {@code MetadataPublications.annotate}; nothing invokes
 * them. The class itself carries no annotation.
 */
@SuppressWarnings("unused")
public final class ComposedHiddenResource {

    private ComposedHiddenResource() {}

    /**
     * Hidden by a composed annotation whose type carries {@code @Operation(hidden = true)}.
     *
     * @param q the query parameter
     */
    @InternalOperationZx
    public void composedOperationHidden(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}

    /**
     * The same operation, carrying a composed annotation whose type carries {@code @Operation(summary =
     * "S")}.
     *
     * @param q the query parameter
     */
    @SummaryOperationZx
    public void composedOperationVisible(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}

    /**
     * Hidden by a composed annotation whose type carries {@code @Hidden}.
     *
     * @param q the query parameter
     */
    @HiddenMethodZx
    public void composedHiddenHidden(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}

    /**
     * The same operation, carrying an annotation whose type carries no documentation annotation.
     *
     * @param q the query parameter
     */
    @PlainMarkerZx
    public void composedHiddenVisible(@Parameter(name = "otherVALUEZX") @QueryParam("q") String q) {}
}
