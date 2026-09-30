// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.test.RestTestNoSecurityModule;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterizes what the {@code web-validation} gate does with a request that carries no body at all,
 * for each shape of body schema a source can hand it, and what the {@code none} strategy does.
 *
 * <p>A {@code POST /orders} with no body and no {@code Content-Type} reaches the gate as a {@code null}
 * body instance. Whether that instance is rejected is decided entirely by the schema the gate holds for
 * the body: the canonical schema generated for a DTO is {@code {"type":"object",...}} and refuses
 * {@code null}, whereas a schema admitting {@code null}, the empty schema, and no schema at all let the
 * request through to the resource, as does a mount with no gate. One expected verdict per shape is
 * pinned here as a fixed literal in the case table, so the table is the whole oracle.
 *
 * <p>The {@code null}-admitting, empty, and absent shapes are supplied by a stub
 * {@link OperationSchemaSource} inside a graph that carries the real {@link WebValidationStrategy}; the
 * canonical shape and the {@code none} strategy use the framework graph with the real
 * {@link AnnotationSchemaSource}. Requests go through a {@link WebClient}, whose response aggregates the
 * body before completing, so an asserted problem body is never a partially delivered one.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AbsentBodyGateCharacterizationTest {

    private static final long WAIT_SECONDS = 15;

    private static final String NULLABLE_OBJECT_SCHEMA = "{\"type\": [\"object\", \"null\"]}";
    private static final String EMPTY_SCHEMA = "{}";

    private Vertx vertx;
    private HttpServer server;
    private WebClient client;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // --- Fixtures ---

    /**
     * The request body type of the one operation.
     *
     * @param id an identifier
     */
    public record Order(String id) {}

    /** Counts how many requests reach the resource method. */
    @Path("/orders")
    public static class OrderResource {

        final AtomicInteger invocations = new AtomicInteger();

        /**
         * Accepts an order and reports that the resource was reached.
         *
         * @param order the bound body, {@code null} when the request carries none
         * @return a fixed marker
         */
        @POST
        @Produces(MediaType.TEXT_PLAIN)
        public String create(Order order) {
            invocations.incrementAndGet();
            return "reached";
        }
    }

    /** Binds the real gate without binding a schema source, so a test supplies the source. */
    @Module
    abstract static class GateWithoutSourceModule {

        @Binds
        @IntoSet
        abstract RequestValidationStrategy webValidationStrategy(WebValidationStrategy strategy);

        @BindsOptionalOf
        abstract Validator validator();
    }

    /** The framework fixture graph with the real gate and a caller-supplied schema source. */
    @Singleton
    @Component(modules = {RestTestFixtureModule.class, RestTestNoSecurityModule.class, GateWithoutSourceModule.class})
    interface StubSourceComponent {

        RestTestMount testMount();

        @Component.Factory
        interface Factory {

            StubSourceComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance RestTestContributions contributions,
                    @BindsInstance OperationSchemaSource source);
        }
    }

    private static RestTestMount mountWithSource(Vertx vertx, OperationSchemaSource source) {
        return DaggerAbsentBodyGateCharacterizationTest_StubSourceComponent.factory()
                .create(vertx, new JsonObject(), RestTestContributions.builder().build(), source)
                .testMount();
    }

    private static RestTestMount mountWithBodySchema(Vertx vertx, String bodySchema) {
        JsonObject schema = new JsonObject(bodySchema);
        return mountWithSource(
                vertx,
                (op, profile) -> OperationSchemas.builder().bodySchema(schema).build());
    }

    // --- Cases: one per schema shape; the verdict is the second column ---

    private static Stream<Arguments> schemaShapes() {
        return Stream.of(
                shape(
                        "canonical generated DTO schema",
                        true,
                        vertx -> MountFixtures.mount(
                                vertx, RestTestContributions.builder().build())),
                shape("object-or-null schema", false, vertx -> mountWithBodySchema(vertx, NULLABLE_OBJECT_SCHEMA)),
                shape("empty schema", false, vertx -> mountWithBodySchema(vertx, EMPTY_SCHEMA)),
                shape(
                        "no body schema",
                        false,
                        vertx -> mountWithSource(vertx, (op, profile) -> OperationSchemas.empty())),
                shape(
                        "none strategy",
                        false,
                        vertx -> MountFixtures.mount(
                                vertx,
                                new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none")),
                                RestTestContributions.builder().build())));
    }

    private static Arguments shape(String name, boolean rejected, Function<Vertx, RestTestMount> mount) {
        return Arguments.of(name, rejected, mount);
    }

    // --- Test ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaShapes")
    void absentBodyVerdictsPerSchemaShape(String shapeName, boolean rejected, Function<Vertx, RestTestMount> mount)
            throws Exception {
        // Given: one POST /orders operation under the configuration the shape names
        OrderResource resource = new OrderResource();
        server = RestTestMounts.startServerBlocking(
                vertx, mount.apply(vertx), Set.of(resource), Duration.ofSeconds(WAIT_SECONDS));
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        // When: a POST with no body and no Content-Type is sent
        HttpResponse<?> response = client.post(server.actualPort(), "127.0.0.1", "/orders")
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);

        // Then: only the canonical schema rejects; every other shape reaches the resource
        if (rejected) {
            assertEquals(400, response.statusCode(), shapeName + ": status");
            assertTrue(
                    response.getHeader("Content-Type").contains("application/problem+json"),
                    shapeName + ": problem media type");
            JsonArray errors = response.bodyAsJsonObject().getJsonArray("errors");
            assertEquals(1, errors.size(), shapeName + ": exactly one detail");
            ValidationErrorDetail detail = errors.getJsonObject(0).mapTo(ValidationErrorDetail.class);
            assertEquals("body", detail.location(), shapeName + ": detail location");
            assertEquals("type", detail.type(), shapeName + ": detail type");
            assertEquals(0, resource.invocations.get(), shapeName + ": the resource must not be reached");
        } else {
            assertEquals(200, response.statusCode(), shapeName + ": status");
            assertEquals("reached", response.bodyAsString(), shapeName + ": body");
            assertEquals(1, resource.invocations.get(), shapeName + ": the resource must be reached once");
        }
    }
}
