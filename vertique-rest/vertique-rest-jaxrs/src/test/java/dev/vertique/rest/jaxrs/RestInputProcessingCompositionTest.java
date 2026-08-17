// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.vertx.ext.web.Router;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
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
}
