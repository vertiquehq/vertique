// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.examples.customresponse.ErrorResponse.ErrorCategory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.UUID;

/**
 * Maps all exceptions to a categorized {@link ErrorResponse} with a UUID error ID.
 *
 * <p>Replaces the framework's default ProblemDetail error format. Since
 * {@link dev.vertique.rest.jaxrs.ExceptionMapperRegistry} registers the
 * {@link dev.vertique.rest.jaxrs.DefaultExceptionMapper} as a single entry at
 * {@code Throwable}, contributing this user-supplied {@code ExceptionMapper<Throwable>}
 * completely replaces it — one class handles all exceptions.
 *
 * <p>Categorization rules:
 * <ul>
 *   <li>{@link ValidationException}, {@link IllegalArgumentException} → {@link ErrorCategory#VALIDATION} (400)</li>
 *   <li>{@link TooManyRequestsException} → {@link ErrorCategory#RATE_LIMITED} (429)</li>
 *   <li>{@link WebApplicationException} with 401/403 → {@link ErrorCategory#SECURITY} (original status)</li>
 *   <li>{@link WebApplicationException} with 400 → {@link ErrorCategory#VALIDATION} (400)</li>
 *   <li>{@link WebApplicationException} with other 4xx → {@link ErrorCategory#BUSINESS} (original status)</li>
 *   <li>Everything else → {@link ErrorCategory#TECHNICAL} (500)</li>
 * </ul>
 */
@Singleton
public class CategorizedExceptionMapper implements ExceptionMapper<Throwable> {

    /**
     * Constructs a new {@code CategorizedExceptionMapper}.
     */
    @Inject
    public CategorizedExceptionMapper() {}

    /**
     * Converts any throwable to a categorized {@link ErrorResponse}.
     *
     * @param throwable the exception to map
     * @return a JAX-RS {@link Response} with the appropriate status and JSON error body
     */
    @Override
    public Response toResponse(Throwable throwable) {
        ErrorCategory category = categorize(throwable);
        int status = statusOf(throwable, category);
        String id = UUID.randomUUID().toString();
        ErrorResponse body = new ErrorResponse(category, throwable.getMessage(), id);

        return Response.status(status).entity(body).type("application/json").build();
    }

    // --- Internal classification helpers ---

    /**
     * Classifies the throwable into an {@link ErrorCategory}.
     *
     * @param t the exception to classify
     * @return the error category
     */
    private ErrorCategory categorize(Throwable t) {
        if (t instanceof TooManyRequestsException) {
            return ErrorCategory.RATE_LIMITED;
        }
        if (t instanceof ValidationException || t instanceof IllegalArgumentException) {
            return ErrorCategory.VALIDATION;
        }
        if (t instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            if (status == 401 || status == 403) {
                return ErrorCategory.SECURITY;
            }
            if (status == 400) {
                return ErrorCategory.VALIDATION;
            }
            if (status >= 400 && status < 500) {
                return ErrorCategory.BUSINESS;
            }
        }
        return ErrorCategory.TECHNICAL;
    }

    /**
     * Determines the HTTP status code for the given throwable.
     *
     * @param t        the exception
     * @param category the already-determined category
     * @return the HTTP status code to use in the response
     */
    private int statusOf(Throwable t, ErrorCategory category) {
        if (t instanceof WebApplicationException wae) {
            return wae.getResponse().getStatus();
        }
        return switch (category) {
            case VALIDATION -> 400;
            case SECURITY -> 403;
            case BUSINESS -> 404;
            case RATE_LIMITED -> 429;
            case TECHNICAL -> 500;
        };
    }
}
