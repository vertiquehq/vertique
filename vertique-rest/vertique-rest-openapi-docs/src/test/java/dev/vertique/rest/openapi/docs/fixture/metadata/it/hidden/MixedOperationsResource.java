// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountFixedZx;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@code /a} of {@link HiddenOperationsApi}: three hidden operations, each hidden a
 * different way or carrying content a document check would refuse, beside one visible operation.
 *
 * <p>Every hidden operation id and hidden name carries the {@code Zx} suffix and every hidden
 * operation declares the tag {@value HiddenOperationsApi#HIDDEN_TAG}; the visible operation does
 * neither. Every method returns {@code void}, so a completed request answers {@code 204}.
 */
@Path(MixedOperationsResource.ROUTE)
public class MixedOperationsResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/a";

    /** The operation id of {@link #readOne}, hidden by {@code @Operation(hidden = true)}. */
    public static final String READ_ONE_ID = "readOneZx";

    /** The operation id of {@link #writeTwo}, hidden by {@code @Hidden}. */
    public static final String WRITE_TWO_ID = "writeTwoZx";

    /** The operation id of {@link #readThree}, visible. */
    public static final String READ_THREE_ID = "readThree";

    /** The operation id of {@link #readFour}, hidden by {@code @Hidden}. */
    public static final String READ_FOUR_ID = "readFourZx";

    /** The hidden query parameter of {@link #writeTwo}. */
    public static final String FLAG = "flagZx";

    /** The query parameter of {@link #readFour}, whose {@code @Parameter} names another location. */
    public static final String MODE = "modeZx";

    /** Creates the resource. */
    public MixedOperationsResource() {}

    /** Handles {@code GET /a/one}, hidden by its {@code @Operation}; answers with an empty response. */
    @GET
    @Path("/one")
    @Operation(operationId = READ_ONE_ID, hidden = true)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    public void readOne() {
        // Nothing to do: a request only proves the route still answers.
    }

    /**
     * Handles {@code POST /a/two}, hidden by {@code @Hidden}; answers with an empty response. Were it
     * published to a protected document, its body's reserved-name guard and its hidden query
     * parameter would each set a root flag.
     *
     * @param flag    query {@value #FLAG}, itself hidden
     * @param account the body, whose type reserves {@code backdoorZx} in its root guard
     */
    @POST
    @Path("/two")
    @Hidden
    @Operation(operationId = WRITE_TWO_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    @Consumes(MediaType.APPLICATION_JSON)
    public void writeTwo(@QueryParam(FLAG) @Parameter(hidden = true) String flag, AccountFixedZx account) {
        // Nothing to do: a request only proves the route still answers.
    }

    /** Handles {@code GET /a/three}, visible; answers with an empty response. */
    @GET
    @Path("/three")
    @Operation(operationId = READ_THREE_ID)
    public void readThree() {
        // Nothing to do: the operation only has to be published.
    }

    /**
     * Handles {@code GET /a/four}, hidden by {@code @Hidden}; answers with an empty response. Its
     * query binding's {@code @Parameter} names the header location, which the document's agreement
     * check would refuse were the operation published.
     *
     * @param mode query {@value #MODE}
     */
    @GET
    @Path("/four")
    @Hidden
    @Operation(operationId = READ_FOUR_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    public void readFour(@QueryParam(MODE) @Parameter(in = ParameterIn.HEADER) String mode) {
        // Nothing to do: a request only proves the route still answers.
    }
}
