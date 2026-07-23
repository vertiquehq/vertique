// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.convert.ParamConversionException;
import dev.vertique.rest.core.convert.ParamConverterNotFoundException;
import dev.vertique.rest.core.convert.ParamSource;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the explicit default exception mappings the framework registers for the two
 * param-conversion failure types (PRD-REST-018 FR-015-08a): {@link ParamConversionException} maps to
 * {@code 400} and {@link ParamConverterNotFoundException} maps to {@code 500}. These are registered
 * explicitly in {@link RestModule#defaultExceptionMapper()} rather than relying on the
 * {@code ValidationException}→400 / {@code Throwable}→500 hierarchy fallback, so this test pins the
 * default contract against an {@link ExceptionMapperRegistry} carrying no application mappers.
 *
 * <p>The app-override path (an {@code ExceptionMapper<ParamConversionException>} replacing the default
 * {@code 400}) is covered separately by {@link ParamConversionMapperOverrideTest}; this class asserts
 * the framework default that the override would otherwise replace.
 */
class ParamConversionDefaultMapperTest {

    /**
     * Builds a registry carrying only the framework {@link DefaultExceptionMapper} (no app mappers), so
     * every assertion exercises the registered default mapping.
     *
     * @return a default-only exception-mapper registry
     */
    private static ExceptionMapperRegistry defaultRegistry() {
        return new ExceptionMapperRegistry(RestModule.defaultExceptionMapper(), Set.of());
    }

    @Test
    @DisplayName("ParamConversionException maps to the default 400 via the explicit registration")
    void paramConversionExceptionMapsTo400() {
        ParamConversionException ex = new ParamConversionException(
                "Failed to convert parameter 'id' to java.util.UUID",
                "id",
                ParamSource.PATH,
                UUID.class,
                new IllegalArgumentException("bad uuid"));

        Response response = defaultRegistry().toResponse(ex);

        assertEquals(400, response.getStatus(), "a conversion failure must default to 400");
    }

    @Test
    @DisplayName("ParamConverterNotFoundException maps to the default 500 via the explicit registration")
    void paramConverterNotFoundExceptionMapsTo500() {
        ParamConverterNotFoundException ex = new ParamConverterNotFoundException(
                "No ParamConverter registered for parameter 'id' of type java.util.UUID",
                "id",
                ParamSource.PATH,
                UUID.class);

        Response response = defaultRegistry().toResponse(ex);

        assertEquals(
                500,
                response.getStatus(),
                "an unsatisfiable converter at request time is a misconfiguration and must default to 500");
    }
}
