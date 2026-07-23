// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.exception.ConflictException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RestModule#defaultExceptionMapper()} maps the persistence-agnostic core
 * exception roots to the correct HTTP status codes.
 *
 * <p>Tests exercise the factory output directly (calling the package-accessible static method) to
 * guard against regressions in the default mapping wiring:
 *
 * <ul>
 *   <li>{@link ConflictException} and subclasses → 409
 *   <li>{@code dev.vertique.core.exception.NotFoundException} and subclasses → 404
 *   <li>{@link BusinessRuleException} and subclasses → 400 (via {@code ValidationException} parent)
 *   <li>{@code dev.vertique.core.exception.UnauthorizedException} and subclasses → 401
 *   <li>{@code dev.vertique.core.exception.ForbiddenException} and subclasses → 403
 * </ul>
 */
class RestModuleDefaultMappingTest {

    // --- Test fixtures ---

    /** Concrete subclass of {@link ConflictException} used to verify hierarchy walking. */
    private static final class DuplicateKeyException extends ConflictException {
        DuplicateKeyException(String message) {
            super(message);
        }
    }

    /** Concrete subclass of {@link BusinessRuleException} used to verify hierarchy walking. */
    private static final class QuotaExceededException extends BusinessRuleException {
        QuotaExceededException(String message) {
            super(message);
        }
    }

    /**
     * Concrete subclass of {@code UnauthorizedException} used to verify hierarchy walking.
     */
    private static final class TokenExpiredException extends dev.vertique.core.exception.UnauthorizedException {
        TokenExpiredException(String message) {
            super(message);
        }
    }

    /**
     * Concrete subclass of {@code ForbiddenException} used to verify hierarchy walking.
     */
    private static final class InsufficientRoleException extends dev.vertique.core.exception.ForbiddenException {
        InsufficientRoleException(String message) {
            super(message);
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("ConflictException should map to 409 Conflict")
    void conflictExceptionShouldMapTo409() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new ConflictException("resource already exists"));

        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("ConflictException subclass should map to 409 via hierarchy walking")
    void conflictExceptionSubclassShouldMapTo409() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new DuplicateKeyException("duplicate key detected"));

        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("dev.vertique.core.exception.NotFoundException should map to 404 Not Found")
    void coreNotFoundExceptionShouldMapTo404() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new dev.vertique.core.exception.NotFoundException("item not found"));

        assertEquals(404, response.getStatus());
    }

    @Test
    @DisplayName("BusinessRuleException should map to 400 Bad Request")
    void businessRuleExceptionShouldMapTo400() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new BusinessRuleException("rule violated"));

        assertEquals(400, response.getStatus());
    }

    @Test
    @DisplayName("BusinessRuleException subclass should map to 400 via hierarchy walking")
    void businessRuleExceptionSubclassShouldMapTo400() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new QuotaExceededException("quota exceeded"));

        assertEquals(400, response.getStatus());
    }

    @Test
    @DisplayName("UnauthorizedException should map to 401 Unauthorized")
    void unauthorizedExceptionShouldMapTo401() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new dev.vertique.core.exception.UnauthorizedException("token missing"));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("UnauthorizedException subclass should map to 401 via hierarchy walking")
    void unauthorizedExceptionSubclassShouldMapTo401() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new TokenExpiredException("token has expired"));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("ForbiddenException should map to 403 Forbidden")
    void forbiddenExceptionShouldMapTo403() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new dev.vertique.core.exception.ForbiddenException("access denied"));

        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("ForbiddenException subclass should map to 403 via hierarchy walking")
    void forbiddenExceptionSubclassShouldMapTo403() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new InsufficientRoleException("requires admin role"));

        assertEquals(403, response.getStatus());
    }
}
