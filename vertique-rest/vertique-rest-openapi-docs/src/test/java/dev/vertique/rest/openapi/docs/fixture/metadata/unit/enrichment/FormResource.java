// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.ws.rs.FormParam;

/**
 * Fixture methods annotating an operation whose only inputs are form fields, read onto synthetic
 * operations by {@code MetadataPublications.annotate}; nothing invokes them. Each method lists one
 * {@code @FormParam} parameter per form field of the operation it annotates. The form request body
 * built from those fields has no body binding, so an annotation documenting it sits on the method.
 *
 * <p>A plain {@code String} form field is not required (a missing value binds as {@code null}); a
 * primitive {@code int} form field's requiredness is unknown. String members carry the sentinel
 * {@code VALUEZX} wherever a value could be echoed. The class itself carries no annotation, so no
 * class-level annotation reaches an operation.
 */
@SuppressWarnings("unused")
public final class FormResource {

    private FormResource() {}

    // ---------------------------------------------------------------------------------------------
    // Examples of form inputs
    // ---------------------------------------------------------------------------------------------

    /**
     * A named example of a form field that references a reusable example.
     *
     * @param note the form field
     */
    public void fieldExampleReference(
            @Parameter(examples = @ExampleObject(name = "e", ref = "#/components/examples/REFVALUEZX"))
                    @FormParam("note")
                    String note) {}

    /**
     * A named example of the form request body whose name is blank.
     *
     * @param note the form field
     */
    @RequestBody(
            content =
                    @Content(
                            mediaType = "application/x-www-form-urlencoded",
                            examples = {@ExampleObject(name = "", value = "1")}))
    public void bodyBlankExampleName(@FormParam("note") String note) {}

    // ---------------------------------------------------------------------------------------------
    // Agreement of form inputs with the runtime
    // ---------------------------------------------------------------------------------------------

    /**
     * A query location on a form field.
     *
     * @param note the form field
     */
    public void fieldQueryLocation(@Parameter(in = ParameterIn.QUERY) @FormParam("note") String note) {}

    /**
     * A schema implementation on the form request body, which binds no type.
     *
     * @param note the form field
     */
    @RequestBody(
            content =
                    @Content(
                            mediaType = "application/x-www-form-urlencoded",
                            schema = @Schema(implementation = ItemDto.class)))
    public void bodyImplementation(@FormParam("note") String note) {}

    /**
     * A requirement on the form request body, which is never required.
     *
     * @param note the form field
     */
    @RequestBody(required = true)
    public void requiredBody(@FormParam("note") String note) {}

    /**
     * A requirement on a form field whose requiredness the runtime leaves unknown.
     *
     * @param count the form field
     */
    public void requiredUnknownField(@Parameter(required = true) @FormParam("count") int count) {}

    /**
     * A description of the form request body.
     *
     * @param note the form field
     */
    @RequestBody(description = "Form body")
    public void describedBody(@FormParam("note") String note) {}
}
