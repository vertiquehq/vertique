// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * With the input-processing engine bound, a route that declares a canonicalizer or sanitizer chain on a
 * body that an application {@link RequestBodyDecoder} claims fails startup, naming the route and the
 * decoder: the decoder would decode the body and the declared chain would never run.
 */
class ApplicationDecoderPolicyStartupTest {

    /** Sanitizer used by the fixtures. */
    public static final class Trimming implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.trim();
        }
    }

    /** Body whose field declares a sanitizer. */
    public record GovernedBody(@Sanitize(Trimming.class) String name) {}

    /** Body with no declared policy. */
    public record PlainBody(String name) {}

    @Path("/governed")
    @PermitAll
    static class FieldPolicyResource {
        @POST
        public Future<String> create(GovernedBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/route-level")
    @PermitAll
    static class RoutePolicyResource {
        @POST
        @Sanitize(Trimming.class)
        public Future<String> create(PlainBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/plain")
    @PermitAll
    static class NoPolicyResource {
        @POST
        public Future<String> create(PlainBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/csv")
    @PermitAll
    static class CsvResource {
        @POST
        @Consumes("text/csv")
        public Future<String> create(GovernedBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/json-only")
    @PermitAll
    static class JsonOnlyResource {
        @POST
        @Consumes("application/json")
        public Future<String> create(GovernedBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/wildcard")
    @PermitAll
    static class WildcardResource {
        @POST
        @Consumes("*/*")
        public Future<String> create(GovernedBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    @Path("/parameterised")
    @PermitAll
    static class ParameterisedResource {
        @POST
        @Consumes("Application/JSON;charset=UTF-8")
        public Future<String> create(GovernedBody body) {
            return Future.succeededFuture(body.name());
        }
    }

    /** An application decoder with a configurable claim and priority. */
    private static final class AppDecoder implements RequestBodyDecoder {
        private final int priority;
        private final BiPredicate<Class<?>, String> claim;

        AppDecoder(int priority, BiPredicate<Class<?>, String> claim) {
            this.priority = priority;
            this.claim = claim;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean canDecode(Class<?> targetType, String contentType) {
            return claim.test(targetType, contentType);
        }

        @Override
        public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
            return null;
        }
    }

    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    private void register(Object resource, List<RequestBodyDecoder> decoders) {
        InputObjectProcessor engine = InputObjectProcessor.createDefault(c -> null, c -> new Trimming());
        RegistrarTestSupport.registerAll(
                new JaxRsRouteRegistrar(),
                Set.of(resource),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                List.of(),
                List.of(),
                null,
                false,
                decoders,
                List.of(),
                "OFF",
                null,
                engine,
                null,
                false);
    }

    private static RequestBodyDecoder claimsEverything() {
        return new AppDecoder(0, (type, contentType) -> true);
    }

    @Test
    @DisplayName(
            "a field policy on a body an application decoder claims fails startup naming the route and the decoder")
    void fieldPolicyFails() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> register(new FieldPolicyResource(), List.of(claimsEverything(), new JsonRequestBodyDecoder())));

        String message = failure.getMessage();
        assertTrue(message.contains("POST /governed"), message);
        assertTrue(message.contains("operationId="), message);
        assertTrue(message.contains(AppDecoder.class.getName()), message);
        assertTrue(message.contains("priority above 1100"), message);
    }

    @Test
    @DisplayName("a route-level chain on a body an application decoder claims fails startup")
    void routeLevelChainFails() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> register(new RoutePolicyResource(), List.of(claimsEverything(), new JsonRequestBodyDecoder())));

        assertTrue(failure.getMessage().contains("POST /route-level"), failure.getMessage());
    }

    @Test
    @DisplayName("a claiming application decoder on a route that declares no chain starts")
    void noPolicyStarts() {
        assertDoesNotThrow(
                () -> register(new NoPolicyResource(), List.of(claimsEverything(), new JsonRequestBodyDecoder())));
    }

    @Test
    @DisplayName("an application decoder behind the framework decoders never claims the body, so the route starts")
    void fallbackDecoderStarts() {
        RequestBodyDecoder fallback = new AppDecoder(2000, (type, contentType) -> true);

        assertDoesNotThrow(() -> register(
                new FieldPolicyResource(),
                List.of(
                        new FormUrlencodedRequestBodyDecoder(),
                        new TextRequestBodyDecoder(),
                        new BinaryRequestBodyDecoder(),
                        new JsonRequestBodyDecoder(),
                        fallback)));
    }

    @Test
    @DisplayName("a decoder that claims only a content type the engine never binds inline leaves the route starting")
    void decoderForOtherContentTypeStarts() {
        RequestBodyDecoder csv = new AppDecoder(0, (type, contentType) -> "text/csv".equals(contentType));

        assertDoesNotThrow(() -> register(new JsonOnlyResource(), List.of(csv, new JsonRequestBodyDecoder())));
        assertDoesNotThrow(() -> register(new CsvResource(), List.of(csv, new JsonRequestBodyDecoder())));
    }

    @Test
    @DisplayName("an unconstrained route is probed with the JSON and form types, so a decoder claiming JSON fails it")
    void unconstrainedRouteProbesJsonType() {
        RequestBodyDecoder json =
                new AppDecoder(0, (type, contentType) -> contentType != null && contentType.contains("json"));

        assertThrows(
                ConfigurationException.class,
                () -> register(new FieldPolicyResource(), List.of(json, new JsonRequestBodyDecoder())));
    }

    @Test
    @DisplayName("a wildcard @Consumes admits JSON, so a catch-all decoder fails startup")
    void wildcardConsumesFails() {
        assertThrows(
                ConfigurationException.class,
                () -> register(new WildcardResource(), List.of(claimsEverything(), new JsonRequestBodyDecoder())));
    }

    @Test
    @DisplayName("a declared type with a parameter and different case is probed in its bare form too")
    void parameterisedConsumesFails() {
        RequestBodyDecoder exactJson = new AppDecoder(0, (type, contentType) -> "application/json".equals(contentType));

        assertThrows(
                ConfigurationException.class,
                () -> register(new ParameterisedResource(), List.of(exactJson, new JsonRequestBodyDecoder())));
    }

    @Test
    @DisplayName("a decoder that throws for a probe value does not claim it and does not break startup")
    void throwingDecoderDoesNotBreakStartup() {
        RequestBodyDecoder throwing = new AppDecoder(0, (type, contentType) -> contentType.isEmpty());

        assertDoesNotThrow(() -> register(new NoPolicyResource(), List.of(throwing, new JsonRequestBodyDecoder())));
        assertDoesNotThrow(() -> register(new FieldPolicyResource(), List.of(throwing, new JsonRequestBodyDecoder())));
    }
}
