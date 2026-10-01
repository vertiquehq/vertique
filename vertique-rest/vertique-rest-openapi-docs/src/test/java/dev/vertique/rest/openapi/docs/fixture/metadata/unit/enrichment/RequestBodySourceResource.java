// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

/**
 * Fixture methods documenting a JSON request body from one of the places a {@code @RequestBody} may
 * sit: the body parameter, the method, or the {@code requestBody} of the method's {@code @Operation}.
 * They are read onto synthetic operations by {@code MetadataPublications.annotate}; nothing invokes
 * them. Each method has exactly one parameter, the body, which carries no JAX-RS parameter
 * annotation. Each place carries its own description, so the published one tells which place was
 * read. The class itself carries no annotation.
 */
@SuppressWarnings("unused")
public final class RequestBodySourceResource {

    private RequestBodySourceResource() {}

    /**
     * A description on the method only.
     *
     * @param body the body
     */
    @RequestBody(description = "mZX")
    public void methodDescription(ItemDto body) {}

    /**
     * A description in the {@code @Operation}'s request body only.
     *
     * @param body the body
     */
    @Operation(requestBody = @RequestBody(description = "oZX"))
    public void operationDescription(ItemDto body) {}

    /**
     * A description on the body parameter beside one on the method.
     *
     * @param body the body
     */
    @RequestBody(description = "mZX")
    public void parameterAndMethodDescriptions(@RequestBody(description = "pZX") ItemDto body) {}

    /**
     * A description on the method beside one in the {@code @Operation}'s request body.
     *
     * @param body the body
     */
    @RequestBody(description = "mZX")
    @Operation(requestBody = @RequestBody(description = "oZX"))
    public void methodAndOperationDescriptions(ItemDto body) {}

    /**
     * A bare all-default request body on the body parameter beside a description on the method.
     *
     * @param body the body
     */
    @RequestBody(description = "mZX")
    public void bareParameterAndMethodDescription(@RequestBody ItemDto body) {}
}
