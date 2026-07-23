// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.client.RestClient;
import dev.vertique.rest.client.RestClientBuilder;
import dev.vertique.rest.core.convert.ParamConverter;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end outbound-conversion integration test for Slice 4.2.
 *
 * <p>Drives two client proxies — the generated static proxy ({@link GeneratedConvClient}, which the
 * {@code vertique-codegen-rest-client} processor emits a {@code _RestClientProxy} companion for) and
 * the JDK reflective proxy ({@link JdkConvClient}, which carries no {@code @RestClient} annotation so
 * no companion is generated and {@link RestClientBuilder} falls back to the JDK path) — against a
 * lightweight echo server that records the exact outbound request line and headers. Both proxies must
 * serialize typed parameters through the shared {@code ParamConversionResolver}:
 *
 * <ul>
 *   <li>a {@code @PathParam UUID} and {@code @QueryParam UUID} render as the canonical UUID string;</li>
 *   <li>a {@code @HeaderParam} enum renders as {@code name()} (not an overridden {@code toString()});</li>
 *   <li>a {@code @QueryParam List<UUID>} renders element-by-element (one query entry per element),
 *       never the {@code List.toString()} bracketed form;</li>
 *   <li>an app-registered {@link ParamConverterBinding} and a JAX-RS {@link ParamConverterProvider}
 *       are honored on the outbound path.</li>
 * </ul>
 *
 * <p><strong>RED status:</strong> compile-RED today — the {@code dev.vertique.rest.core.convert}
 * types are not on the example/rest-client classpath, and the fluent
 * {@code paramConverterBinding(...)} / {@code paramConverterProvider(...)} builder setters do not
 * exist. Once those compile, the assertions are still behavior-RED: the outbound path currently
 * serializes every value with {@code Object.toString()} and never consults a resolver, so the enum
 * header would echo {@code "not-the-name"}, the {@code List<UUID>} would echo a single bracketed
 * entry, and the custom-type params would echo their default {@code toString()} rather than the
 * supplied converter's output.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OutboundConversionIT {

    // --- Echo server capturing the last outbound request ---

    /** Records the most recent request URI and selected headers so assertions can read them. */
    static final AtomicReference<String> lastUri = new AtomicReference<>();

    static final AtomicReference<String> lastColorHeader = new AtomicReference<>();

    /** Shared Vert.x instance injected by {@link VertxExtension}; set in {@link #setUp}. */
    static Vertx vertx;

    /** Actual bound port of the echo server; set in {@link #setUp} after {@code listen(0)} resolves. */
    static int echoPort;

    /** The running echo server; closed in {@link #tearDown}. */
    static HttpServer echoServer;

    /** Minimal verticle that echoes 200 and captures the request URI + {@code X-Color} header. */
    public static final class EchoVerticle extends AbstractVerticle {

        @Override
        public void start(Promise<Void> startPromise) {
            Router router = Router.router(this.vertx);
            router.route().handler(ctx -> {
                lastUri.set(ctx.request().uri());
                lastColorHeader.set(ctx.request().getHeader("X-Color"));
                ctx.response().putHeader("Content-Type", "text/plain").end("ok");
            });
            this.vertx
                    .createHttpServer()
                    .requestHandler(router)
                    .listen(0)
                    .onSuccess(s -> {
                        echoServer = s;
                        echoPort = s.actualPort();
                        startPromise.complete();
                    })
                    .onFailure(startPromise::fail);
        }
    }

    /**
     * Deploys the echo server verticle on the injected {@link Vertx} instance.
     *
     * <p>The server binds to port {@code 0} (OS-allocated ephemeral); the actual port is read from the
     * {@link HttpServer} reference after {@code listen(0)} resolves and stored in {@link #echoPort}.
     *
     * @param injectedVertx the Vert.x instance provided by {@link VertxExtension}
     * @param ctx the test context used to signal setup completion or failure
     */
    @BeforeAll
    static void setUp(Vertx injectedVertx, VertxTestContext ctx) {
        vertx = injectedVertx;
        vertx.deployVerticle(new EchoVerticle())
                .onSuccess(id -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    /**
     * Closes the echo server after all tests complete.
     *
     * <p>The injected {@link Vertx} instance is managed by {@link VertxExtension} and must not be
     * closed here. Only the server handle needs to be released explicitly.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (echoServer != null) {
            echoServer.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Custom domain types + converters ---

    /** Enum whose {@code toString()} deliberately differs from {@code name()}. */
    enum Color {
        RED {
            @Override
            public String toString() {
                return "not-the-name";
            }
        },
        GREEN
    }

    /** App custom type converted via a {@link ParamConverterBinding}. */
    record Sku(String code) {}

    /** App custom type converted via a JAX-RS {@link ParamConverterProvider}. */
    record Tag(String label) {}

    static final ParamConverter<Sku> SKU_CONVERTER = new ParamConverter<>() {
        @Override
        public Sku fromString(String value) {
            return new Sku(value);
        }

        @Override
        public String toString(Sku value) {
            return "sku-" + value.code();
        }
    };

    static final ParamConverterProvider TAG_PROVIDER = new ParamConverterProvider() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> jakarta.ws.rs.ext.ParamConverter<T> getConverter(
                Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != Tag.class) {
                return null;
            }
            return (jakarta.ws.rs.ext.ParamConverter<T>) new jakarta.ws.rs.ext.ParamConverter<Tag>() {
                @Override
                public Tag fromString(String value) {
                    return new Tag(value);
                }

                @Override
                public String toString(Tag value) {
                    return "tag-" + value.label();
                }
            };
        }
    };

    // --- Client interfaces ---

    /**
     * Generated-proxy client: the {@code @RestClient} annotation makes the processor emit a companion.
     *
     * <p>Returns {@code Future<Void>} so the dispatcher skips JSON deserialization of the echo
     * server's plain-text body — this test only asserts the <em>outbound</em> request shape.
     */
    @RestClient(name = "generatedConv")
    @Path("/conv")
    interface GeneratedConvClient {
        @GET
        @Path("/{id}")
        Future<Void> call(
                @PathParam("id") UUID id,
                @QueryParam("color") Color color,
                @HeaderParam("X-Color") Color header,
                @QueryParam("ids") List<UUID> ids,
                @QueryParam("sku") Sku sku,
                @QueryParam("tag") Tag tag);
    }

    /**
     * JDK-proxy client: no {@code @RestClient} annotation → no generated companion → JDK fallback.
     *
     * <p>Returns {@code Future<Void>} so the dispatcher skips JSON deserialization of the echo
     * server's plain-text body — this test only asserts the <em>outbound</em> request shape.
     */
    @Path("/conv")
    interface JdkConvClient {
        @GET
        @Path("/{id}")
        Future<Void> call(
                @PathParam("id") UUID id,
                @QueryParam("color") Color color,
                @HeaderParam("X-Color") Color header,
                @QueryParam("ids") List<UUID> ids,
                @QueryParam("sku") Sku sku,
                @QueryParam("tag") Tag tag);
    }

    // --- Helpers ---

    private RestClientBuilder builder() {
        return RestClientBuilder.create(vertx)
                .baseUrl("http://localhost:" + echoPort)
                .paramConverterBinding(new ParamConverterBinding<>(Sku.class, SKU_CONVERTER))
                .paramConverterProvider(TAG_PROVIDER);
    }

    private void assertOutbound(VertxTestContext ctx, Future<Void> call, boolean expectGeneratedProxyShape) {
        call.onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
            String uri = lastUri.get();
            // PathParam + QueryParam UUID render as canonical UUID strings.
            assertTrue(uri.contains("00000000-0000-0000-0000-000000000001"), "UUID path/query canonical form: " + uri);
            // Enum query renders as name(), not the overridden toString().
            assertTrue(uri.contains("color=RED"), "enum query name(): " + uri);
            assertTrue(!uri.contains("not-the-name"), "no toString() leakage: " + uri);
            // List<UUID> renders element-by-element, never the bracketed List.toString().
            assertTrue(!uri.contains("%5B") && !uri.contains("["), "no List.toString() bracket: " + uri);
            // App ParamConverterBinding + JAX-RS provider applied.
            assertTrue(uri.contains("sku=sku-"), "ParamConverterBinding applied: " + uri);
            assertTrue(uri.contains("tag=tag-"), "JAX-RS provider applied: " + uri);
            // Enum header renders as name() via the resolver, not the overridden toString().
            assertEquals("RED", lastColorHeader.get(), "enum header name()");
            ctx.completeNow();
        })));
    }

    private GeneratedConvClient generatedClient() {
        GeneratedConvClient client = builder().build(GeneratedConvClient.class);
        assertTrue(
                client.getClass().getSimpleName().endsWith("_RestClientProxy"),
                "expected generated proxy, got " + client.getClass().getName());
        return client;
    }

    private JdkConvClient jdkClient() {
        JdkConvClient client = builder().build(JdkConvClient.class);
        assertTrue(
                Proxy.isProxyClass(client.getClass()),
                "expected JDK proxy, got " + client.getClass().getName());
        return client;
    }

    private UUID id() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private List<UUID> ids() {
        return List.of(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
    }

    // --- Tests ---

    @Test
    @DisplayName("generated proxy serializes typed outbound params via the resolver")
    void generatedProxyOutboundViaResolver(VertxTestContext ctx) {
        GeneratedConvClient client = generatedClient();
        Future<Void> call = client.call(id(), Color.RED, Color.RED, ids(), new Sku("123"), new Tag("vip"));
        assertOutbound(ctx, call, true);
    }

    @Test
    @DisplayName("JDK proxy serializes typed outbound params via the resolver")
    void jdkProxyOutboundViaResolver(VertxTestContext ctx) {
        JdkConvClient client = jdkClient();
        Future<Void> call = client.call(id(), Color.RED, Color.RED, ids(), new Sku("123"), new Tag("vip"));
        assertOutbound(ctx, call, false);
    }
}
