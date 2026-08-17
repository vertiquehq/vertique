// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Composition-gate tests for {@link JaxRsRouteRegistrar}: a route that declares canonicalization or
 * sanitization while no {@link InputObjectProcessor} is bound fails startup, naming every offending
 * route in one aggregated error.
 *
 * <p>Before this gate the absence of the optional engine binding was silent, so an application whose
 * DTOs declared {@code @Sanitize} booted and served requests with none of it running. Both shapes are
 * covered here: an invocation-level chain declared on the resource method, and a policy declared on
 * the body DTO itself (which only {@link InputObjectProcessor#declaresPolicies} can see).
 *
 * <p>The registrar also composes each route's wire &rarr; Java name projection here rather than on the
 * request path, so a body type whose projection cannot be composed fails startup instead of failing
 * every request that touches it.
 */
class RestInputProcessingCompositionTest {

    private JaxRsRouteRegistrar registrar;
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        registrar = new JaxRsRouteRegistrar();
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    // --- Fixtures ---

    /** Inert sanitizer; the gate is about the declaration, never about what the chain does. */
    public static class NoopSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }

    /** Body DTO declaring a field-level policy of its own. */
    public record GovernedDto(@Sanitize(NoopSanitizer.class) String text) {}

    /** Body DTO declaring nothing. */
    public record PlainDto(String text) {}

    /**
     * Body DTO whose two properties claim the same {@code @JsonAlias}. The wire-name projection cannot
     * decide which property owns the key without disagreeing with Jackson, which resolves the same
     * collision in hash order — so composing the projection for this type is a configuration failure.
     */
    public static class DuplicateAliasDto {
        @JsonAlias({"shared"})
        public String alpha;

        @JsonAlias({"shared"})
        public String beta;
    }

    /** Resource whose body type carries an unresolvable wire-name projection. */
    @Path("/projection")
    static class UnprojectableBodyResource {

        @POST
        public Future<String> accept(DuplicateAliasDto dto) {
            return Future.succeededFuture(dto.alpha);
        }
    }

    /** Resource whose two routes declare policies in the two different ways the gate must see. */
    @Path("/governed")
    static class PolicyDeclaringResource {

        @POST
        @Path("/route-chain")
        @Sanitize(NoopSanitizer.class)
        public Future<String> withRouteChain(String body) {
            return Future.succeededFuture(body);
        }

        @POST
        @Path("/dto")
        public Future<String> withPolicyDeclaringBody(GovernedDto dto) {
            return Future.succeededFuture(dto.text());
        }
    }

    /**
     * Resource whose binary body carries a declared chain. Canonicalization and sanitization act on
     * string values, of which a {@code byte[]} or {@code Buffer} body has none, so the declaration can
     * never run no matter which modules are installed.
     */
    @Path("/binary")
    static class BinaryBodyResource {

        @POST
        @Path("/bytes")
        @Sanitize(NoopSanitizer.class)
        public Future<String> uploadBytes(byte[] payload) {
            return Future.succeededFuture(String.valueOf(payload.length));
        }

        @POST
        @Path("/buffer")
        @Sanitize(NoopSanitizer.class)
        public Future<String> uploadBuffer(Buffer payload) {
            return Future.succeededFuture(String.valueOf(payload.length()));
        }
    }

    /** Resource whose binary body declares nothing — the ordinary upload shape, which must still boot. */
    @Path("/binary-free")
    static class UngovernedBinaryBodyResource {

        @POST
        public Future<String> upload(byte[] payload) {
            return Future.succeededFuture(String.valueOf(payload.length));
        }
    }

    /**
     * Resource declaring a policy on a parameter source the engine never sees. {@code @Context},
     * {@code FILE_UPLOADS}, and {@code ENTITY_PARTS} values are {@code FileContentVerifier} territory,
     * so the gate must not demand an engine binding that would change nothing for them.
     */
    @Path("/excluded-source")
    static class ExcludedSourcePolicyResource {

        @GET
        public Future<String> read(@Sanitize(NoopSanitizer.class) @Context RoutingContext routingContext) {
            return Future.succeededFuture("ok");
        }
    }

    /** Resource that declares no policy anywhere in its parameter graph. */
    @Path("/free")
    static class PolicyFreeResource {

        @GET
        public Future<String> readAll() {
            return Future.succeededFuture("all");
        }

        @POST
        public Future<String> create(PlainDto dto) {
            return Future.succeededFuture(dto.text());
        }
    }

    /** Pass-through engine standing in for a bound {@code SanitizationModule}. */
    private static final class PassThroughProcessor implements InputObjectProcessor {
        @Override
        public Object processInput(
                Object input,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            return input;
        }
    }

    private void register(Set<Object> resources, InputObjectProcessor objectProcessor) {
        RegistrarTestSupport.registerAll(
                registrar,
                resources,
                router,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                objectProcessor,
                null,
                false);
    }

    // --- Tests ---

    @Test
    @DisplayName("startup fails with one aggregated error naming every route with declared but unbound policies")
    void shouldFailStartupListingEveryRouteWithDeclaredButUnboundPolicies() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> register(Set.of(new PolicyDeclaringResource(), new PolicyFreeResource()), null),
                "a declared policy with no bound InputObjectProcessor must fail startup");

        String message = failure.getMessage();
        assertTrue(
                message.contains("withRouteChain"),
                "the aggregated error must name the route declaring an invocation-level chain: " + message);
        assertTrue(
                message.contains("withPolicyDeclaringBody"),
                "the aggregated error must name the route whose body DTO declares a policy: " + message);
        assertTrue(
                message.contains(GovernedDto.class.getName()),
                "the aggregated error must name the type whose declared policy cannot run: " + message);
        assertTrue(
                message.contains("InputObjectProcessor"),
                "the aggregated error must name the missing binding: " + message);
        assertTrue(
                !message.contains("readAll") && !message.contains("create"),
                "a policy-free route must not appear in the error: " + message);

        // The same resources start once the engine is bound — the gate is about the missing binding.
        setUp();
        assertDoesNotThrow(
                () -> register(Set.of(new PolicyDeclaringResource()), new PassThroughProcessor()),
                "declared policies with a bound processor must register normally");
    }

    @Test
    @DisplayName("an application whose routes declare no policies starts without an InputObjectProcessor")
    void shouldStartWhenNoRouteDeclaresPolicies() {
        assertDoesNotThrow(
                () -> register(Set.of(new PolicyFreeResource()), null),
                "the gate must not fire for routes that declare nothing to process");
    }

    @Test
    @DisplayName("a binary body carrying a declared policy fails startup and points at FileContentVerifier")
    void shouldFailStartupWhenABinaryBodyDeclaresAPolicy() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> register(Set.of(new BinaryBodyResource()), new PassThroughProcessor()),
                "a policy declared on a binary body cannot run even with the engine bound, so it must fail startup");

        String message = failure.getMessage();
        assertTrue(message.contains("uploadBytes"), "the error must name the byte[] route: " + message);
        assertTrue(message.contains("uploadBuffer"), "the error must name the Buffer route: " + message);
        assertTrue(
                message.contains("FileContentVerifier"),
                "the error must name the control that does apply to binary content: " + message);
        assertTrue(
                message.contains("FileUpload") && message.contains("multipart"),
                "the error must state that FileContentVerifier covers multipart FileUpload parts, "
                        + "not a raw binary body parameter: " + message);

        // The gate is about the declaration, not about binary bodies: an ordinary upload still boots.
        setUp();
        assertDoesNotThrow(
                () -> register(Set.of(new UngovernedBinaryBodyResource()), new PassThroughProcessor()),
                "a binary body declaring no policy must register normally");
    }

    @Test
    @DisplayName("a policy declared on a parameter source the engine never sees does not fail startup")
    void shouldNotFailStartupForAPolicyOnAnExcludedParameterSource() {
        assertDoesNotThrow(
                () -> register(Set.of(new ExcludedSourcePolicyResource()), null),
                "a @Context parameter's values never reach the engine, so demanding an engine binding "
                        + "would tell the operator to install a module that changes nothing");
    }

    @Test
    @DisplayName("a body type whose wire-name projection cannot be composed fails startup, not the first request")
    void shouldFailStartupWhenABodyTypesProjectionCannotBeComposed() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> register(Set.of(new UnprojectableBodyResource()), new PassThroughProcessor()),
                "the projection is composed at route registration, so an unresolvable one fails startup");

        String message = failure.getMessage();
        assertTrue(
                message.contains(DuplicateAliasDto.class.getName()),
                "the failure must name the body type whose projection cannot be composed: " + message);
        assertTrue(message.contains("shared"), "the failure must name the contested wire name: " + message);

        // A body type whose projection composes cleanly still registers, so the warm-up is not a new
        // blanket startup cost that rejects ordinary DTOs.
        setUp();
        assertDoesNotThrow(
                () -> register(Set.of(new PolicyFreeResource()), new PassThroughProcessor()),
                "an ordinary body type must register normally");
    }
}
