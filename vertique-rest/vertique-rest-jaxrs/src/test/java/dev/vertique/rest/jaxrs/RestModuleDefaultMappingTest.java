// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 *   <li>{@link TooManyRequestsException} and subclasses → 429, with {@code Retry-After} when present
 *       (T020) — registered explicitly at the {@code Throwable}-level default; {@link
 *       TooManyRequestsException} extends {@code VertiqueException} directly (T021 W1), so no
 *       inherited {@code BusinessRuleException}/{@code ValidationException} → 400 fallback can ever
 *       apply to it
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

    /** Concrete subclass of {@link TooManyRequestsException} used to verify hierarchy walking. */
    private static final class CustomTooManyRequestsException extends TooManyRequestsException {
        CustomTooManyRequestsException(String message) {
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

    /**
     * {@link BusinessRuleException} subclass (two levels below {@link ValidationException}) that
     * carries structured violations, used to verify the carrier branch is reached through
     * hierarchy walking.
     */
    private static final class CarrierValidationException extends BusinessRuleException implements ViolationCarrier {
        private final List<ViolationDetail> violations;

        CarrierValidationException(String message, List<ViolationDetail> violations) {
            super(message);
            this.violations = violations;
        }

        @Override
        public List<ViolationDetail> violations() {
            return violations;
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

    @Test
    @DisplayName("TooManyRequestsException should map to 429 Too Many Requests with no Retry-After when absent")
    void tooManyRequestsExceptionShouldMapTo429WithoutRetryAfter() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new TooManyRequestsException("slow down"));

        assertEquals(429, response.getStatus());
        assertNull(response.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("TooManyRequestsException should map to 429 Too Many Requests with Retry-After when present")
    void tooManyRequestsExceptionShouldMapTo429WithRetryAfter() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new TooManyRequestsException("slow down", Duration.ofSeconds(7)));

        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("TooManyRequestsException subclass should map to 429 via hierarchy walking, never the "
            + "ValidationException/BusinessRuleException 400 fallback (T021 W1 — no longer a shared ancestor)")
    void tooManyRequestsExceptionSubclassShouldMapTo429() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response response = mapper.toResponse(new CustomTooManyRequestsException("quota exceeded"));

        assertEquals(429, response.getStatus());
    }

    @Test
    @DisplayName("ValidationException implementing ViolationCarrier should map to 400 ValidationProblemDetail")
    void violationCarrierValidationExceptionShouldMapToValidationProblemDetail() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();
        CarrierValidationException exception = new CarrierValidationException(
                "invalid decision payload",
                List.of(new ViolationDetail(
                        "/comment", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3))));

        Response response = mapper.toResponse(exception);

        assertEquals(400, response.getStatus());
        assertEquals("application", response.getMediaType().getType());
        assertEquals("problem+json", response.getMediaType().getSubtype());
        ValidationProblemDetail body = assertInstanceOf(ValidationProblemDetail.class, response.getEntity());
        assertEquals("invalid decision payload", body.detail());
        assertEquals(1, body.errors().size());
        ValidationErrorDetail error = body.errors().get(0);
        assertEquals("/comment", error.path());
        assertEquals("must have a maximum length of 3", error.detail());
        assertNull(error.location());
        assertEquals("maxLength", error.type());
        assertEquals(Map.of("maxLength", 3), error.args());
    }

    @Test
    @DisplayName("application mapper for a carrier subclass still wins over the default carrier rendering")
    void applicationMapperForACarrierSubclassStillWinsOverTheDefaultCarrierRendering() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper()
                .on(CarrierValidationException.class, ex -> Response.status(422)
                        .entity("custom: " + ex.getMessage())
                        .build());
        CarrierValidationException exception = new CarrierValidationException(
                "invalid decision payload",
                List.of(new ViolationDetail("/comment", "must have a maximum length of 3", "maxLength", Map.of())));

        Response response = mapper.toResponse(exception);

        assertEquals(422, response.getStatus());
        assertEquals("custom: invalid decision payload", response.getEntity());
    }

    @Test
    @DisplayName("plain ValidationException stays message-only and BeanValidationException output is unchanged")
    void plainValidationExceptionStaysMessageOnlyAndBeanValidationExceptionIsUnchanged() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();

        Response plain = mapper.toResponse(new ValidationException("bad"));

        assertEquals(400, plain.getStatus());
        assertFalse(plain.getEntity() instanceof ValidationProblemDetail);
        ProblemDetail plainBody = assertInstanceOf(ProblemDetail.class, plain.getEntity());
        assertEquals("bad", plainBody.detail());

        Response bean = mapper.toResponse(new BeanValidationException(
                "validation failed",
                List.of(new ViolationDetail("name", "must not be blank", "required", Map.of("min", 1)))));

        assertEquals(400, bean.getStatus());
        ValidationProblemDetail beanBody = assertInstanceOf(ValidationProblemDetail.class, bean.getEntity());
        assertEquals("validation failed", beanBody.detail());
        assertEquals(1, beanBody.errors().size());
        ValidationErrorDetail error = beanBody.errors().get(0);
        assertEquals("name", error.path());
        assertEquals("must not be blank", error.detail());
        assertNull(error.location());
        assertEquals("required", error.type());
        assertEquals(Map.of("min", 1), error.args());
    }
}
