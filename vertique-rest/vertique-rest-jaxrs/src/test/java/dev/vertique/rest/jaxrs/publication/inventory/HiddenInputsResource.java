// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation declares every hiding-marker placement the operation inventory
 * reads: markers on method parameters, on composite fields and record components, on the method
 * parameter that binds a composite, and on a composite type.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is the reflection-path twin of {@link GeneratedHiddenInputsResource}, and every composite it binds is a reflection-path fixture too. The parameter order is load-bearing.
 */
@Path("/hidden")
public class HiddenInputsResource {

    /**
     * {@code GET /hidden}.
     *
     * @param debug        query {@code debug}, marked {@code @Parameter(hidden = true)} (index 0)
     * @param verbose      query {@code verbose}, marked {@code @Parameter(hidden = false)} (index 1)
     * @param internal     header {@code X-Internal}, marked {@code @Schema(hidden = true)}, since
     *                     {@code @Hidden} has no parameter target (index 2)
     * @param fields       composite with a {@code @Hidden} field and an unmarked field (index 3)
     * @param components   record composite with a hidden and an explicitly visible component
     *                     (index 4)
     * @param hiddenBean   composite of unmarked fields, bound by a parameter marked
     *                     {@code @Parameter(hidden = true)} (index 5)
     * @param hiddenParams record composite of unmarked components, bound by a parameter marked
     *                     {@code @Schema(hidden = true)} (index 6)
     * @param typeBean     composite whose type is marked {@code @Hidden} (index 7)
     * @param typeParams   record composite whose type is marked {@code @Hidden} (index 8)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listHidden(
            @QueryParam("debug") @Parameter(hidden = true) String debug,
            @QueryParam("verbose") @Parameter(hidden = false) String verbose,
            @HeaderParam("X-Internal") @Schema(hidden = true) String internal,
            @BeanParam HiddenFieldBean fields,
            HiddenComponentParams components,
            @BeanParam @Parameter(hidden = true) PropagatedBean hiddenBean,
            @Schema(hidden = true) PropagatedParams hiddenParams,
            @BeanParam HiddenTypeBean typeBean,
            HiddenTypeParams typeParams) {
        return "hidden";
    }
}
