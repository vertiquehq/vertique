// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that {@link RestTestFixtureModule} hands a consumer graph the framework's <em>real</em>
 * REST collaborators — including the encoders, decoders, and context resolvers that are
 * package-private in {@code vertique-rest-jaxrs} and {@code vertique-rest-core} and therefore
 * unreachable by any other means — and that contributions are additive and never sorted by the
 * fixture itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FixtureGraphTest {

    /** Timeout for the single router build this test awaits. */
    private static final long AWAIT_SECONDS = 5;

    private static Vertx vertx;

    @BeforeAll
    static void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // --- Production defaults reach the consumer graph ---

    @Test
    @DisplayName("the graph yields all six production response body encoders in OrderedExtension order")
    void graphYieldsAllSixProductionEncodersInSortedOrder() {
        FixtureSelfTestComponent component = component(new JsonObject(), RestTestContributions.none());

        assertThat(simpleNames(component.sortedResponseBodyEncoders()))
                .containsExactly(
                        "SseBodyEncoder",
                        "BufferBodyEncoder",
                        "ByteArrayBodyEncoder",
                        "ReadStreamBodyEncoder",
                        "StringBodyEncoder",
                        "JsonBodyEncoder");
    }

    @Test
    @DisplayName("the graph yields all four production request body decoders in OrderedExtension order")
    void graphYieldsAllFourProductionDecodersInSortedOrder() {
        FixtureSelfTestComponent component = component(new JsonObject(), RestTestContributions.none());

        assertThat(simpleNames(component.sortedRequestBodyDecoders()))
                .containsExactly(
                        "BinaryRequestBodyDecoder",
                        "FormUrlencodedRequestBodyDecoder",
                        "TextRequestBodyDecoder",
                        "JsonRequestBodyDecoder");
    }

    @Test
    @DisplayName("the graph's context resolution reaches rest-core's package-private resolvers")
    void contextResolutionResolvesRoutingContext() {
        FixtureSelfTestComponent component = component(new JsonObject(), RestTestContributions.none());
        RoutingContext ctx = routingContextStub();

        Optional<RoutingContext> fromGraph = component.restContextResolution().resolve(RoutingContext.class, ctx);
        Optional<RoutingContext> fromEmptyChain =
                new RestContextResolution(Set.of()).resolve(RoutingContext.class, ctx);

        assertThat(fromGraph)
                .as("the fixture graph must carry the package-private RoutingContextResolver")
                .isPresent();
        assertThat(fromGraph.orElseThrow()).isSameAs(ctx);
        assertThat(fromEmptyChain)
                .as("a chain with no resolvers is the control — it cannot resolve anything")
                .isEmpty();
    }

    // --- Contribution semantics ---

    @Test
    @DisplayName("a contributed encoder is placed by priority, not by insertion order")
    void contributedEncoderSortsByPriorityNotInsertionOrder() {
        RestTestContributions contributions = RestTestContributions.builder()
                .addResponseBodyEncoder(new Priority900Encoder())
                .build();

        FixtureSelfTestComponent component = component(new JsonObject(), contributions);

        assertThat(simpleNames(component.sortedResponseBodyEncoders()))
                .as("priority 900 out-ranks every framework default, so it must run first")
                .startsWith("Priority900Encoder", "SseBodyEncoder");
    }

    @Test
    @DisplayName("contributions are additive — every production encoder survives alongside them")
    void contributionsAreAdditiveNotReplacing() {
        RestTestContributions contributions = RestTestContributions.builder()
                .addResponseBodyEncoder(new Priority900Encoder())
                .build();

        FixtureSelfTestComponent component = component(new JsonObject(), contributions);

        assertThat(simpleNames(component.sortedResponseBodyEncoders()))
                .contains(
                        "SseBodyEncoder",
                        "BufferBodyEncoder",
                        "ByteArrayBodyEncoder",
                        "ReadStreamBodyEncoder",
                        "StringBodyEncoder",
                        "JsonBodyEncoder",
                        "Priority900Encoder");
    }

    // --- The jaxrs.validationStrategy trap ---

    @Test
    @DisplayName("a router builds when jaxrs.validationStrategy=none; the default web-validation id fails")
    void noneStrategyConfigResolvesAtRouterBuild() throws Exception {
        JsonObject noneConfig = new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));

        Router router = buildRouter(component(noneConfig, RestTestContributions.none()));

        assertThat(router).isNotNull();

        // Control: RestModule alone binds only the `none` strategy, while JaxRsConfig defaults the
        // configured id to `web-validation`. An empty-config graph therefore cannot resolve a
        // strategy at router-build time. This is the trap a consumer must configure around.
        FixtureSelfTestComponent defaulted = component(new JsonObject(), RestTestContributions.none());
        assertThatThrownBy(() -> buildRouter(defaulted))
                .isInstanceOf(RestConfigurationException.class)
                .hasMessageContaining("web-validation");
    }

    // --- Helpers ---

    /**
     * Builds a self-test graph over the fixture module.
     *
     * @param config        the application configuration
     * @param contributions the additive contributions
     * @return the assembled component
     */
    private static FixtureSelfTestComponent component(JsonObject config, RestTestContributions contributions) {
        return DaggerFixtureSelfTestComponent.factory().create(vertx, config, contributions);
    }

    /**
     * Builds a router from a component's real mount factory over a single JAX-RS resource.
     *
     * @param component the graph to build from
     * @return the built router
     * @throws Exception when the build fails or times out
     */
    private static Router buildRouter(FixtureSelfTestComponent component) throws Exception {
        // Through the handle's package-private accessor rather than a second component method: the
        // synchronous RestConfigurationException asserted below is what RestTestMounts.router turns
        // into a failed future, so this test has to call createRouter directly.
        JaxRsRouterMount mount =
                component.testMount().factory().create("/*", "openapi.json", Set.of(new PingResource()));
        return mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Maps extensions to their simple class names. The framework codecs are package-private in their
     * own modules, so a name comparison is the only way to assert on them from here.
     *
     * @param extensions the extensions to name
     * @return the simple class names in list order
     */
    private static List<String> simpleNames(List<?> extensions) {
        return extensions.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    /**
     * Returns a {@link RoutingContext} stand-in. {@code RoutingContextResolver} matches on
     * {@code type.isInstance(ctx)}, so a JDK proxy over the interface is a faithful — and
     * dependency-free — probe; no method on it is ever invoked by the resolver chain.
     *
     * @return a routing context proxy
     */
    private static RoutingContext routingContextStub() {
        return (RoutingContext) Proxy.newProxyInstance(
                FixtureGraphTest.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RoutingContextStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** JAX-RS resource used only to give the mount something to register. */
    @Path("/fixture")
    public static class PingResource {

        /**
         * Returns a constant body.
         *
         * @return the literal {@code "pong"}
         */
        @GET
        @Path("/ping")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "fixturePing")
        public String ping() {
            return "pong";
        }
    }

    /** Test encoder that out-ranks every framework default (priority {@code 900} &lt; {@code 999}). */
    private static final class Priority900Encoder implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == String.class;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            return new BufferedBody(Buffer.buffer(String.valueOf(entity)), "text/plain", null);
        }

        @Override
        public int priority() {
            return 900;
        }
    }
}
