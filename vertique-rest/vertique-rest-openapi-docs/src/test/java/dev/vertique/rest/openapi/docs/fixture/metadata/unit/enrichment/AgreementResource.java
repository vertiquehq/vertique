// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import dev.vertique.rest.openapi.docs.fixture.metadata.dto.OtherDto;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;

/**
 * Fixture methods whose annotations are read onto synthetic operations by {@code
 * MetadataPublications.annotate}; nothing invokes them. Each method lists exactly one parameter per
 * binding of the operation it annotates, in builder order: a parameter binding's parameter carries
 * the JAX-RS annotation of its location and name, and a body parameter carries none.
 *
 * <p>Parameter types are coherent with the requiredness the synthetic binding states: a plain
 * {@code String} query parameter is not required (a missing value binds as {@code null}), a primitive
 * {@code int} query parameter's requiredness is unknown, and a {@code @NotNull String} query
 * parameter is required. String members carry the sentinel {@code VALUEZX} wherever a value could be
 * echoed. The class itself carries no annotation, so no class-level annotation reaches an operation.
 */
@SuppressWarnings("unused")
public final class AgreementResource {

    private AgreementResource() {}

    // ---------------------------------------------------------------------------------------------
    // Attributes that contradict the runtime
    // ---------------------------------------------------------------------------------------------

    /** (a) An operation id other than the runtime's. */
    @Operation(operationId = "otherVALUEZX")
    public void otherOperationId() {}

    /**
     * (b) A parameter name other than the binding's.
     *
     * @param q the query parameter
     */
    public void renamedParameter(@Parameter(name = "queryVALUEZX") @QueryParam("q") String q) {}

    /**
     * (c) A location other than the binding's.
     *
     * @param q the query parameter
     */
    public void relocatedParameter(@Parameter(in = ParameterIn.HEADER) @QueryParam("q") String q) {}

    /**
     * (d) A requirement on a parameter the runtime does not require.
     *
     * @param q the query parameter, bound as {@code null} when missing
     */
    public void requiredOptionalParameter(@Parameter(required = true) @QueryParam("q") String q) {}

    /**
     * (e) A requirement on a body whose schema accepts {@code null}.
     *
     * @param body the body
     */
    public void requiredNullableBody(@RequestBody(required = true) Object body) {}

    /**
     * (f) A body implementation other than the bound type.
     *
     * @param body the body
     */
    public void otherBodyImplementation(
            @RequestBody(content = @Content(schema = @Schema(implementation = OtherDto.class))) ItemDto body) {}

    /**
     * (k) A body media type the operation does not consume.
     *
     * @param body the body
     */
    public void unconsumedBodyMediaType(@RequestBody(content = @Content(mediaType = "application/xml")) ItemDto body) {}

    /**
     * (l) Parameter content, which the runtime never binds.
     *
     * @param q the query parameter
     */
    public void parameterContent(@Parameter(content = @Content(mediaType = "text/plain")) @QueryParam("q") String q) {}

    /**
     * (r) An element implementation other than the bound element type.
     *
     * @param q the multi-valued query parameter
     */
    public void otherElementImplementation(
            @Parameter(array = @ArraySchema(schema = @Schema(implementation = Integer.class))) @QueryParam("q")
                    List<String> q) {}

    // ---------------------------------------------------------------------------------------------
    // Attributes that are ignored with a warning
    // ---------------------------------------------------------------------------------------------

    /**
     * (m) A schema-shaping member of the parameter's schema.
     *
     * @param q the query parameter
     */
    public void parameterMaxLength(@Parameter(schema = @Schema(maxLength = 5)) @QueryParam("q") String q) {}

    /**
     * (s) An array member other than its element schema.
     *
     * @param q the multi-valued query parameter
     */
    public void arrayMinItems(
            @Parameter(array = @ArraySchema(minItems = 1, schema = @Schema(implementation = String.class)))
                    @QueryParam("q")
                    List<String> q) {}

    /**
     * (t) A requirement on a parameter whose requiredness the runtime leaves unknown.
     *
     * @param q the query parameter
     */
    public void requiredUnknownParameter(@Parameter(required = true) @QueryParam("q") int q) {}

    /**
     * (u) Schema-shaping members on two parameters of one operation.
     *
     * @param q the first query parameter
     * @param r the second query parameter
     */
    public void twoIgnoredMembers(
            @Parameter(schema = @Schema(maxLength = 5)) @QueryParam("q") String q,
            @Parameter(schema = @Schema(format = "fVALUEZX")) @QueryParam("r") String r) {}

    /**
     * (v) A schema-shaping member of the body's schema.
     *
     * @param body the body
     */
    public void bodyMinProperties(
            @RequestBody(content = @Content(schema = @Schema(implementation = ItemDto.class, minProperties = 1)))
                    ItemDto body) {}

    // ---------------------------------------------------------------------------------------------
    // Attributes that agree with the runtime
    // ---------------------------------------------------------------------------------------------

    /**
     * (g) The binding's own name and location.
     *
     * @param q the query parameter
     */
    public void agreeingNameAndLocation(@Parameter(name = "q", in = ParameterIn.QUERY) @QueryParam("q") String q) {}

    /**
     * (h) A requirement on a path parameter.
     *
     * @param id the path parameter
     */
    public void requiredPathParameter(@Parameter(required = true) @PathParam("id") String id) {}

    /** (i) The runtime's own operation id. */
    @Operation(operationId = "listItems")
    public void sameOperationId() {}

    /**
     * (j) The bound type as the parameter's implementation.
     *
     * @param q the query parameter
     */
    public void sameImplementation(
            @Parameter(schema = @Schema(implementation = String.class)) @QueryParam("q") String q) {}

    /**
     * (n) Documentation members of the parameter's own schema.
     *
     * @param q the query parameter
     */
    public void parameterSchemaDocumentation(
            @Parameter(schema = @Schema(description = "dVALUEZX", title = "tVALUEZX", example = "eVALUEZX"))
                    @QueryParam("q")
                    String q) {}

    /**
     * (w) A parameter description beside the schema's own description, deprecation, and external
     * documentation.
     *
     * @param q the query parameter
     */
    public void parameterDescriptionWins(
            @Parameter(
                            description = "pVALUEZX",
                            schema =
                                    @Schema(
                                            description = "dVALUEZX",
                                            deprecated = true,
                                            externalDocs =
                                                    @ExternalDocumentation(url = "https://docs.example.test/xVALUEZX")))
                    @QueryParam("q")
                    String q) {}

    /**
     * (x) Documentation members of the body's own schema, with no request-body description.
     *
     * @param body the body
     */
    public void bodySchemaDocumentation(
            @RequestBody(
                            content =
                                    @Content(
                                            schema =
                                                    @Schema(
                                                            implementation = ItemDto.class,
                                                            description = "bVALUEZX",
                                                            title = "btVALUEZX",
                                                            example = "beVALUEZX",
                                                            deprecated = true)))
                    ItemDto body) {}

    /**
     * (z) Media-type examples beside the body schema's own example.
     *
     * @param body the body
     */
    public void bodyExamplesWin(
            @RequestBody(
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "b1", value = "{\"a\": 1}"),
                                            schema = @Schema(implementation = ItemDto.class, example = "ezVALUEZX")))
                    ItemDto body) {}

    /**
     * (y) Parameter examples beside the schema's own example.
     *
     * @param q the query parameter
     */
    public void parameterExamplesWin(
            @Parameter(examples = @ExampleObject(name = "e1", value = "1"), schema = @Schema(example = "eVALUEZX"))
                    @QueryParam("q")
                    String q) {}

    /**
     * (o) A requirement on a body whose schema rejects {@code null}.
     *
     * @param body the body
     */
    public void requiredBody(@RequestBody(required = true) ItemDto body) {}

    /**
     * (p) A requirement on a parameter the runtime requires.
     *
     * @param q the query parameter
     */
    public void requiredRequiredParameter(@Parameter(required = true) @QueryParam("q") @NotNull String q) {}

    /**
     * (q) The bound element type as the element implementation.
     *
     * @param q the multi-valued query parameter
     */
    public void sameElementImplementation(
            @Parameter(array = @ArraySchema(schema = @Schema(implementation = String.class))) @QueryParam("q")
                    List<String> q) {}

    // ---------------------------------------------------------------------------------------------
    // Parameter names that differ from the binding's only in case
    // ---------------------------------------------------------------------------------------------
    //
    // A case variant of the bound name carries no sentinel, because a sentinel cannot reveal whether
    // the variant is echoed; the cases assert the variant's own spelling absent instead.

    /**
     * A header name that differs from the binding's only in ASCII case.
     *
     * @param trace the header parameter
     */
    public void caseFoldedHeaderName(@Parameter(name = "x-trace") @HeaderParam("X-Trace") String trace) {}

    /**
     * The binding's own header name, spelled exactly.
     *
     * @param trace the header parameter
     */
    public void exactHeaderName(@Parameter(name = "X-Trace") @HeaderParam("X-Trace") String trace) {}

    /**
     * A header name that differs from the binding's by more than case.
     *
     * @param trace the header parameter
     */
    public void renamedHeaderName(@Parameter(name = "X-TraceVALUEZX") @HeaderParam("X-Trace") String trace) {}

    /**
     * A header name equal to the binding's only under Unicode case folding: its {@code K} is U+212A
     * KELVIN SIGN, which Unicode case mapping folds to an ASCII {@code k} but an ASCII-only comparison
     * does not.
     *
     * @param key the header parameter
     */
    public void kelvinHeaderName(@Parameter(name = "X-\u212Aey") @HeaderParam("X-Key") String key) {}

    /**
     * A query name that differs from the binding's only in case.
     *
     * @param qname the query parameter
     */
    public void caseFoldedQueryName(@Parameter(name = "QNAME") @QueryParam("qname") String qname) {}

    /**
     * A cookie name that differs from the binding's only in case.
     *
     * @param session the cookie parameter
     */
    public void caseFoldedCookieName(@Parameter(name = "SESSION") @CookieParam("session") String session) {}

    /**
     * A path name that differs from the binding's only in case.
     *
     * @param itemid the path parameter
     */
    public void caseFoldedPathName(@Parameter(name = "ITEMID") @PathParam("itemid") String itemid) {}
}
