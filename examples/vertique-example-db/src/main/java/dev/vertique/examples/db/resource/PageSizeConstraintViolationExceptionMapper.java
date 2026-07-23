// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import dev.vertique.db.query.PageSizeConstraintViolationException;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Maps {@link PageSizeConstraintViolationException} to an HTTP 400 response with RFC 9457 Problem
 * Details, including structured extension attributes for the page size constraints.
 */
public class PageSizeConstraintViolationExceptionMapper
        implements ExceptionMapper<PageSizeConstraintViolationException> {

    @Inject
    public PageSizeConstraintViolationExceptionMapper() {}

    @Override
    public Response toResponse(PageSizeConstraintViolationException exception) {
        var problem = ProblemDetail.builder()
                .status(400)
                .title("Bad Request")
                .detail(exception.getMessage())
                .extension("requestedPageSize", exception.requestedPageSize())
                .extension("minPageSize", exception.minPageSize())
                .extension("maxPageSize", exception.maxPageSize())
                .build();

        return Response.status(400)
                .type(new MediaType("application", "problem+json"))
                .entity(problem)
                .build();
    }
}
