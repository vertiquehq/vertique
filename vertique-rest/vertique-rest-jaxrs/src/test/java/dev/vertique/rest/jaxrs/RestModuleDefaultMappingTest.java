// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.core.validation.ViolationPathForm;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
     * carries structured violations and does not override the path form, used to verify the carrier
     * branch is reached through hierarchy walking and that the default form applies.
     */
    private static class CarrierValidationException extends BusinessRuleException implements ViolationCarrier {
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

    /** A carrier whose detail paths are JSON Pointers and which declares so. */
    private static final class PointerCarrierValidationException extends CarrierValidationException {
        PointerCarrierValidationException(String message, List<ViolationDetail> violations) {
            super(message, violations);
        }

        @Override
        public ViolationPathForm violationPathForm() {
            return ViolationPathForm.JSON_POINTER;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private Logger mappingLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureMappingLogs() {
        mappingLogger = (Logger) LoggerFactory.getLogger(RestModule.class);
        previousLevel = mappingLogger.getLevel();
        mappingLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        mappingLogger.addAppender(appender);
    }

    @AfterEach
    void releaseMappingLogs() {
        mappingLogger.detachAppender(appender);
        appender.stop();
        mappingLogger.setLevel(previousLevel);
    }

    /** Renders {@code exception} through the default mapping and returns each error entry as JSON. */
    private static List<JsonNode> render(ValidationException exception) {
        Response response = RestModule.defaultExceptionMapper().toResponse(exception);

        assertEquals(400, response.getStatus());
        ValidationProblemDetail body = assertInstanceOf(ValidationProblemDetail.class, response.getEntity());
        return body.errors().stream()
                .map(error -> JSON.<JsonNode>valueToTree(error))
                .toList();
    }

    /** The formatted messages of the DEBUG events captured so far. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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
        PointerCarrierValidationException exception = new PointerCarrierValidationException(
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
        assertEquals("/comment", error.pointer());
        assertNull(error.parameter());
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
    @DisplayName("plain ValidationException stays message-only and BeanValidationException entries carry no location")
    void plainValidationExceptionStaysMessageOnlyAndBeanValidationExceptionCarriesNoLocation() {
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
        assertNull(error.pointer());
        assertNull(error.parameter());
        assertEquals("must not be blank", error.detail());
        assertNull(error.location());
        assertEquals("required", error.type());
        assertEquals(Map.of("min", 1), error.args());
    }

    @Test
    @DisplayName("carriers render by their declared path form, never by what the path looks like")
    void carriersRenderByTheirDeclaredPathForm() {
        // Given: a pointer carrier (with the truncation entry), a default-form carrier whose path looks
        // like a pointer, and a pointer carrier with a nested list position
        PointerCarrierValidationException pointerCarrier = new PointerCarrierValidationException(
                "invalid decision payload",
                List.of(
                        new ViolationDetail("/comment", "must have a maximum length of 3", "maxLength", null),
                        new ViolationDetail("", "further violations omitted", "truncated", null)));
        CarrierValidationException defaultCarrier = new CarrierValidationException(
                "invalid carrier",
                List.of(new ViolationDetail("/looks/like/a/pointer", "must be valid", "pattern", null)));
        PointerCarrierValidationException nestedPointerCarrier = new PointerCarrierValidationException(
                "invalid payload",
                List.of(new ViolationDetail("/items/0/name", "must not be blank", "required", null)));

        // When: the default mapping renders each
        List<JsonNode> pointerEntries = render(pointerCarrier);
        List<JsonNode> defaultEntries = render(defaultCarrier);
        List<JsonNode> nestedEntries = render(nestedPointerCarrier);

        // Then: a pointer carrier's entries carry pointer, the empty pointer stays present, and no entry
        // carries a location or a path
        assertEquals(2, pointerEntries.size());
        assertEquals("/comment", pointerEntries.get(0).path("pointer").asText());
        assertEquals(
                "must have a maximum length of 3",
                pointerEntries.get(0).path("detail").asText());
        assertTrue(pointerEntries.get(1).has("pointer"), "the empty pointer must not be omitted");
        assertEquals("", pointerEntries.get(1).get("pointer").asText());
        assertEquals("truncated", pointerEntries.get(1).path("type").asText());
        assertEquals(1, nestedEntries.size());
        assertEquals("/items/0/name", nestedEntries.get(0).path("pointer").asText());
        for (JsonNode entry : List.of(pointerEntries.get(0), pointerEntries.get(1), nestedEntries.get(0))) {
            assertFalse(entry.has("location"), () -> "unexpected location in " + entry);
            assertFalse(entry.has("path"), () -> "unexpected path in " + entry);
            assertFalse(entry.has("parameter"), () -> "unexpected parameter in " + entry);
        }

        // And: the default-form carrier's entry has neither member, although its path looks like a pointer
        assertEquals(1, defaultEntries.size());
        JsonNode defaultEntry = defaultEntries.get(0);
        assertFalse(defaultEntry.has("pointer"), () -> "unexpected pointer in " + defaultEntry);
        assertFalse(defaultEntry.has("parameter"), () -> "unexpected parameter in " + defaultEntry);
        assertFalse(defaultEntry.has("path"), () -> "unexpected path in " + defaultEntry);
        assertFalse(defaultEntry.has("location"), () -> "unexpected location in " + defaultEntry);
        assertEquals("must be valid", defaultEntry.path("detail").asText());
        assertEquals("pattern", defaultEntry.path("type").asText());
    }

    @Test
    @DisplayName("a pointer carrier's paths are not logged")
    void pointerCarrierPathsAreNotLogged() {
        PointerCarrierValidationException pointerCarrier = new PointerCarrierValidationException(
                "invalid decision payload", List.of(new ViolationDetail("/comment", "too long", "maxLength", null)));

        render(pointerCarrier);

        assertTrue(loggedMessages().isEmpty(), () -> "unexpected events: " + loggedMessages());
    }

    @Test
    @DisplayName("BeanValidationException entries carry no location member and log a neutralized path, never a key or "
            + "the message")
    void beanValidationExceptionEntriesCarryNoLocationMember() {
        // Given: Java paths holding a map key shaped to break a naive renderer, a nested list in a map, and
        // the root, each with a message that must never be logged
        BeanValidationException exception = new BeanValidationException(
                "validation failed",
                List.of(
                        new ViolationDetail("byKey[ZZKEY].name", "ZZMESSAGE", "required", null),
                        new ViolationDetail("byKey[a]ZZKEY].name", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a].ZZKEY", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a][0].ZZKEY", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a].x", "ZZMESSAGE", "required", null),
                        new ViolationDetail("", "ZZMESSAGE", "required", null)));

        // When: rendered
        List<JsonNode> entries = render(exception);

        // Then: every entry has no location member and keeps detail and type
        assertEquals(6, entries.size());
        for (JsonNode entry : entries) {
            assertFalse(entry.has("pointer"), () -> "unexpected pointer in " + entry);
            assertFalse(entry.has("parameter"), () -> "unexpected parameter in " + entry);
            assertFalse(entry.has("path"), () -> "unexpected path in " + entry);
            assertFalse(entry.has("location"), () -> "unexpected location in " + entry);
            assertEquals("ZZMESSAGE", entry.path("detail").asText());
            assertEquals("required", entry.path("type").asText());
        }

        // And: the DEBUG events show the path up to its first bracket, with the type, one per violation
        List<String> messages = loggedMessages();
        assertEquals(2, messages.stream().filter(m -> m.contains("byKey[]")).count(), () -> messages.toString());
        assertEquals(3, messages.stream().filter(m -> m.contains("x[]")).count(), () -> messages.toString());
        assertEquals(5, messages.size(), () -> "the empty path must log nothing: " + messages);
        assertTrue(messages.stream().allMatch(m -> m.contains("required")), () -> messages.toString());

        // And: no event holds a key the client chose, any index, or the message
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage();
            assertFalse(text.contains("ZZKEY"), () -> "key logged: " + text);
            assertFalse(text.contains("ZZMESSAGE"), () -> "message logged: " + text);
            assertFalse(text.contains("[a"), () -> "key text logged: " + text);
            assertFalse(text.contains("[0"), () -> "index logged: " + text);
            for (Object argument : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                String rendered = String.valueOf(argument);
                assertFalse(rendered.contains("ZZKEY"), () -> "key logged: " + rendered);
                assertFalse(rendered.contains("ZZMESSAGE"), () -> "message logged: " + rendered);
            }
        }
    }

    @Test
    @DisplayName("a default-form carrier logs at most 100 path events per response and still renders every entry")
    void aDefaultFormCarrierLogsAtMostOneHundredPathEventsPerResponse() {
        List<ViolationDetail> details = IntStream.range(0, 101)
                .mapToObj(i -> new ViolationDetail("field" + i, "must not be blank", "required", null))
                .toList();

        List<JsonNode> entries = render(new BeanValidationException("validation failed", details));

        assertEquals(101, entries.size());
        assertEquals(100, appender.list.size());
    }
}
