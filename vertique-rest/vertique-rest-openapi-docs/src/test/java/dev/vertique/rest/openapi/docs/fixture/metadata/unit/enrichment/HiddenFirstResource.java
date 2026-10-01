// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenFieldZx;
import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

/**
 * Twin fixture methods for operations that would fail a document check if they were published: each
 * {@code ...Hidden} method carries a hidden marker, either {@code @Operation(hidden = true)} or
 * {@code @Hidden}, and its {@code ...Visible} twin is identical without it. They are read onto synthetic
 * operations by {@code MetadataPublications.annotate}; nothing invokes them. Twins list the same
 * parameters, one per binding in builder order. The class itself carries no annotation.
 */
@SuppressWarnings("unused")
public final class HiddenFirstResource {

    private HiddenFirstResource() {}

    /** (a) A hidden operation whose path renders like a visible operation's. */
    @Operation(hidden = true)
    public void renderedPathHidden() {}

    /** (a) The same operation, visible. */
    public void renderedPathVisible() {}

    /**
     * (b) A hidden operation whose body component key equals a visible operation's.
     *
     * @param body the body
     */
    @Operation(hidden = true)
    public void componentKeyHidden(ItemDto body) {}

    /**
     * (b) The same operation, visible.
     *
     * @param body the body
     */
    public void componentKeyVisible(ItemDto body) {}

    /**
     * (c) A hidden operation whose captured body has no provenance.
     *
     * @param body the body
     */
    @Hidden
    public void unverifiedBodyHidden(ItemDto body) {}

    /**
     * (c) The same operation, visible.
     *
     * @param body the body
     */
    public void unverifiedBodyVisible(ItemDto body) {}

    /**
     * (d) A hidden operation whose query parameter's captured schema holds {@code propertyNames}.
     *
     * @param filter the query parameter
     */
    @Operation(hidden = true)
    public void propertyNamesHidden(@QueryParam("filter") String filter) {}

    /**
     * (d) The same operation, visible.
     *
     * @param filter the query parameter
     */
    public void propertyNamesVisible(@QueryParam("filter") String filter) {}

    /**
     * (e) A hidden operation whose query parameter's captured schema references another document.
     *
     * @param source the query parameter
     */
    @Hidden
    public void externalReferenceHidden(@QueryParam("source") String source) {}

    /**
     * (e) The same operation, visible.
     *
     * @param source the query parameter
     */
    public void externalReferenceVisible(@QueryParam("source") String source) {}

    /**
     * (f) A hidden operation whose path parameter is flagged hidden.
     *
     * @param id the path parameter
     */
    @Operation(hidden = true)
    public void hiddenPathHidden(@PathParam("id") String id) {}

    /**
     * (f) The same operation, visible.
     *
     * @param id the path parameter
     */
    public void hiddenPathVisible(@PathParam("id") String id) {}

    /** (g) A hidden operation declaring an operation id other than the runtime's. */
    @Operation(hidden = true, operationId = "otherVALUEZX")
    public void otherOperationIdHidden() {}

    /** (g) The same operation, visible. */
    @Operation(operationId = "otherVALUEZX")
    public void otherOperationIdVisible() {}

    /** (h) A hidden operation declaring tag {@code shared} with description {@code A}. */
    @Operation(hidden = true)
    @Tag(name = "shared", description = "A")
    public void sharedTagHidden() {}

    /** (h) The same operation, visible. */
    @Tag(name = "shared", description = "A")
    public void sharedTagVisible() {}

    /** (h) A visible operation declaring tag {@code shared} with description {@code B}. */
    @Tag(name = "shared", description = "B")
    public void sharedTagOther() {}

    /**
     * (i) A visible operation whose query parameter, flagged hidden by the inventory, declares
     * another name and location.
     *
     * @param debug the query parameter
     */
    public void hiddenInputDisagrees(
            @Parameter(in = ParameterIn.HEADER, name = "otherVALUEZX") @QueryParam("debug") String debug) {}

    /**
     * (j) A hidden operation whose body type has a {@code @Hidden} member.
     *
     * @param body the body
     */
    @Hidden
    public void hiddenMemberHidden(AccountHiddenFieldZx body) {}

    /**
     * (j) The same operation, visible.
     *
     * @param body the body
     */
    public void hiddenMemberVisible(AccountHiddenFieldZx body) {}

    /**
     * (k) A hidden operation whose query parameter of unknown requiredness declares a requirement and
     * a schema-shaping member.
     *
     * @param q the query parameter
     */
    @Operation(hidden = true)
    public void warnedHidden(@Parameter(required = true, schema = @Schema(maxLength = 5)) @QueryParam("q") int q) {}

    /**
     * (k) The same operation, visible.
     *
     * @param q the query parameter
     */
    public void warnedVisible(@Parameter(required = true, schema = @Schema(maxLength = 5)) @QueryParam("q") int q) {}
}
