// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end routing tests for the plain-{@link Router} {@link JaxRsRouterMount} (PRD-REST-017 slice 9,
 * core routing rewrite). These drive the real {@code createRouter()} path under the {@code none}
 * validation strategy (no schema-gen dependency), exercising:
 *
 * <ul>
 *   <li>route creation without any {@code openapi.json} on the classpath (FR-001);</li>
 *   <li>JAX-RS path-template translation, including a regex-constrained {@code {id:\d+}} bound
 *       <em>by name</em> via a named capture group, and multi-parameter templates (FR-002);</li>
 *   <li>{@code BoundRequest} population under {@code none} (binding decoupled from validation, FR-026)
 *       for undeclared query/header/cookie parameters, {@code @BeanParam}, and repeated query values
 *       (collection vs scalar) (FR-024);</li>
 *   <li>the preserved handler execution order (auth → gate → contributors → invoker);</li>
 *   <li>fail-fast strategy selection on an unknown {@code validationStrategy} id.</li>
 * </ul>
 *
 * <p>The {@code openapi.json} absence is structural: no contract file is referenced anywhere in this
 * test, and the mount no longer loads one. Body-read-once is verified by counting reads of a single
 * shared {@code BoundRequest} stashed on the routing context.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class AnnotationDrivenRoutingIT {

    /**
     * Response header stamped on every answer this class's own servers write, and the value it
     * carries. It makes one question answerable from a CI log alone: did our server answer at all?
     * An empty body under a correct status has two unrelated causes — the framework pipeline writing
     * no entity, or another process answering on the port — and the failure text has been unable to
     * tell them apart.
     */
    private static final String SERVER_MARKER_HEADER = "X-Vertique-Test-Server";

    private static final String SERVER_ID = UUID.randomUUID().toString();

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static Vertx vertx;
    private static HttpClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link HttpClient} once for the
     * entire test class. Allocating a fresh client per test accumulates netty channel pools that
     * surface under full-reactor load as connection failures.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = v.createHttpClient();
        ctx.completeNow();
    }

    /**
     * Closes the per-test {@link HttpServer}. The shared {@link HttpClient} is closed only in
     * {@link #tearDownClass(VertxTestContext)}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared {@link HttpClient} after all tests in the class have run.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Resource fixtures ---

    /** Resource declaring a plain {@code {id}} path template and a regex-constrained {@code {id:\d+}}. */
    @Path("/users")
    public static class UserResource {

        /**
         * Echoes the bound path id for {@code GET /users/{id}}.
         *
         * @param id the path id
         * @return the id echoed back
         */
        @GET
        @Path("/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "getUser")
        public String getUser(@PathParam("id") String id) {
            return "id=" + id;
        }
    }

    /** Resource whose path template constrains the id to digits via {@code {id:\d+}}. */
    @Path("/num")
    public static class NumericResource {

        /**
         * Echoes the numeric path id, proving the regex-constrained param binds by name.
         *
         * @param id the digit-constrained path id
         * @return the id echoed back
         */
        @GET
        @Path("/{id:\\d+}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "getNumeric")
        public String getNumeric(@PathParam("id") int id) {
            return "num=" + id;
        }
    }

    /** Resource with a two-parameter path template {@code /a/{p}/b/{q}}. */
    @Path("/multi")
    public static class MultiParamResource {

        /**
         * Echoes both path params for {@code GET /multi/a/{p}/b/{q}}.
         *
         * @param p the first path param
         * @param q the second path param
         * @return both params echoed back
         */
        @GET
        @Path("/a/{p}/b/{q}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "getMulti")
        public String getMulti(@PathParam("p") String p, @PathParam("q") String q) {
            return "p=" + p + ",q=" + q;
        }
    }

    /** Resource binding query, header, and cookie parameters that no spec declares. */
    @Path("/bind")
    public static class BindingResource {

        /**
         * Echoes a query param so the test can assert binding under the {@code none} strategy.
         *
         * @param lang the query param
         * @return the bound value echoed back
         */
        @GET
        @Path("/query")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bindQuery")
        public String bindQuery(@QueryParam("extra") String extra) {
            return "extra=" + extra;
        }

        /**
         * Echoes a header param (case-insensitive binding).
         *
         * @param custom the header param
         * @return the bound value echoed back
         */
        @GET
        @Path("/header")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bindHeader")
        public String bindHeader(@HeaderParam("X-Custom") String custom) {
            return "custom=" + custom;
        }

        /**
         * Echoes a cookie param.
         *
         * @param session the cookie param
         * @return the bound value echoed back
         */
        @GET
        @Path("/cookie")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bindCookie")
        public String bindCookie(@CookieParam("session") String session) {
            return "session=" + session;
        }

        /**
         * Echoes a repeated collection query param.
         *
         * @param ids the repeated query values
         * @return the bound list echoed back
         */
        @GET
        @Path("/ids")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bindIds")
        public String bindIds(@QueryParam("ids") List<Integer> ids) {
            return "ids=" + ids;
        }

        /**
         * Echoes a repeated scalar query param (first-value rule).
         *
         * @param id the query value(s); only the first binds
         * @return the bound scalar echoed back
         */
        @GET
        @Path("/id")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bindScalarId")
        public String bindScalarId(@QueryParam("id") int id) {
            return "id=" + id;
        }
    }

    /** Bean carrying two query-bound fields for the {@code @BeanParam} test. */
    public static class SearchParams {
        @QueryParam("q")
        public String q;

        @QueryParam("page")
        public int page;
    }

    /** Resource binding a {@code @BeanParam} from query fields. */
    @Path("/search")
    public static class SearchResource {

        /**
         * Echoes the bean's bound fields for {@code GET /search?q=...&page=...}.
         *
         * @param params the bean param populated from query fields
         * @return the bound bean fields echoed back
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "search")
        public String search(@BeanParam SearchParams params) {
            return "q=" + params.q + ",page=" + params.page;
        }
    }

    // --- Test 1: route created with no openapi.json present ---

    @Test
    @DisplayName("Route is created and dispatches with no openapi.json on the classpath")
    void routeCreatedWithNoOpenApiJsonPresent(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new UserResource()), HttpMethod.GET, "/users/42", body -> {
            assertEquals("id=42", body);
        });
    }

    // --- Test 2: regex path template translates + binds by name ---

    @Test
    @DisplayName("JAX-RS {id:\\d+} translates to a regex route whose param binds by name; non-digits 404")
    void javaxPathTemplateWithRegexTranslates(VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new NumericResource()), port -> exchange(HttpMethod.GET, port, "/num/42", null, null)
                .compose(first -> {
                    ctx.verify(() -> withDiagnostic(first, () -> {
                        // Binding by NAME: the named capture group (?<id>\d+) populates pathParams("id"),
                        // which DefaultBoundRequest reads by name, so the coerced int reaches the method.
                        assertEquals(
                                "200|num=42",
                                first.statusCode() + "|" + first.body().toString(),
                                "regex-constrained param must bind by name");
                    }));
                    return exchange(HttpMethod.GET, port, "/num/abc", null, null);
                })
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> withDiagnostic(
                            result, () -> assertEquals(404, result.statusCode(), "non-digit path must not match")));
                    ctx.completeNow();
                })));
    }

    // --- Test 3: multi-param path template ---

    @Test
    @DisplayName("Path template with multiple params translates and binds each by name")
    void pathTemplateWithMultipleParamsTranslates(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new MultiParamResource()), HttpMethod.GET, "/multi/a/x/b/y", body -> {
            assertEquals("p=x,q=y", body);
        });
    }

    // --- Test 5: BoundRequest populated under none (query) ---

    @Test
    @DisplayName("BoundRequest binds a query param under the none strategy (no gate)")
    void boundRequestPopulatedForAllStrategies(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new BindingResource()), HttpMethod.GET, "/bind/query?extra=hello", body -> {
            assertEquals("extra=hello", body);
        });
    }

    // --- Test 7/8/9: undeclared query/header/cookie still bound ---

    @Test
    @DisplayName("Undeclared query param is still bound (binding is not spec-limited)")
    void undeclaredQueryParamStillBound(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new BindingResource()), HttpMethod.GET, "/bind/query?extra=world", body -> {
            assertEquals("extra=world", body);
        });
    }

    @Test
    @DisplayName("Undeclared header param is still bound, case-insensitively")
    void undeclaredHeaderParamStillBound(VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new BindingResource()), port -> {
            exchange(HttpMethod.GET, port, "/bind/header", req -> req.putHeader("x-custom", "value"), null)
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() -> withDiagnostic(
                                result,
                                () -> assertEquals("custom=value", result.body().toString())));
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("Undeclared cookie param is still bound")
    void undeclaredCookieParamStillBound(VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new BindingResource()), port -> {
            exchange(HttpMethod.GET, port, "/bind/cookie", req -> req.putHeader("Cookie", "session=abc"), null)
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() -> withDiagnostic(
                                result,
                                () -> assertEquals("session=abc", result.body().toString())));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 10: @BeanParam query fields bound ---

    @Test
    @DisplayName("@BeanParam query fields are bound")
    void beanParamQueryFieldBound(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new SearchResource()), HttpMethod.GET, "/search?q=foo&page=3", body -> {
            assertEquals("q=foo,page=3", body);
        });
    }

    // --- Test 11: repeated query collection param ---

    @Test
    @DisplayName("Repeated query values bind to a List in declaration order")
    void repeatedQueryValuesCollectionParam(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new BindingResource()), HttpMethod.GET, "/bind/ids?ids=1&ids=2&ids=3", body -> {
            assertEquals("ids=[1, 2, 3]", body);
        });
    }

    // --- Test 12: repeated query scalar param (first-value) ---

    @Test
    @DisplayName("Repeated query values for a scalar param bind the first value")
    void repeatedQueryValuesScalarParam(VertxTestContext ctx) {
        request(vertx, ctx, Set.of(new BindingResource()), HttpMethod.GET, "/bind/id?id=1&id=2", body -> {
            assertEquals("id=1", body);
        });
    }

    // --- Test 6: body read once (gate + invoker share one BoundRequest) ---

    /** Resource echoing a JSON body field for the body-read-once test. */
    @Path("/echo")
    public static class EchoResource {

        /**
         * Echoes the {@code name} field of the JSON body for {@code POST /echo}.
         *
         * @param payload the request body bean
         * @return the echoed name
         */
        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "echo")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }
    }

    /** Simple JSON body bean. */
    public static class Payload {
        public String name;
    }

    // --- Non-object body dispatch: text/plain String, octet-stream byte[], JSON-array List<Dto> ---

    /**
     * Resource accepting non-object request bodies that previously 500'd during binding (the
     * body-path bug): a {@code text/plain} body to a {@code String} param, an
     * {@code application/octet-stream} body to a {@code byte[]} param, and a JSON-array body to a
     * {@code List<Item>} param. Each echoes a deterministic projection proving the body reached the
     * resource as the right Java type, not a raw buffer or a 500.
     */
    @Path("/body")
    public static class BodyResource {

        /**
         * Echoes a {@code text/plain} body bound to a {@link String} param.
         *
         * @param text the plain-text body
         * @return the body echoed back, proving it bound as a String
         */
        @jakarta.ws.rs.POST
        @Path("/text")
        @jakarta.ws.rs.Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bodyText")
        public String text(String text) {
            return "text=" + text;
        }

        /**
         * Echoes the length of an {@code application/octet-stream} body bound to a {@code byte[]} param.
         *
         * @param bytes the raw binary body
         * @return the byte count, proving the binary body bound as a {@code byte[]}
         */
        @jakarta.ws.rs.POST
        @Path("/bytes")
        @jakarta.ws.rs.Consumes(MediaType.APPLICATION_OCTET_STREAM)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bodyBytes")
        public String bytes(byte[] bytes) {
            return "bytes=" + (bytes == null ? "null" : bytes.length);
        }

        /**
         * Echoes the {@code name} fields of a JSON-array body bound to a {@code List<Item>} param.
         *
         * @param items the deserialized list of items
         * @return the joined item names, proving the array body bound as a typed List
         */
        @jakarta.ws.rs.POST
        @Path("/list")
        @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bodyList")
        public String list(List<Item> items) {
            return "names=" + items.stream().map(i -> i.name).collect(java.util.stream.Collectors.joining(","));
        }
    }

    /** JSON array element DTO for the {@code List<Item>} body test. */
    public static class Item {
        public String name;
    }

    @Test
    @DisplayName("A text/plain body binds to a String param and reaches the resource (no 500)")
    void textPlainBodyBindsToStringParam(VertxTestContext ctx) {
        postBody(
                vertx,
                ctx,
                "/body/text",
                MediaType.TEXT_PLAIN,
                Buffer.buffer("hello world"),
                statusAndBody -> assertEquals("200|text=hello world", statusAndBody));
    }

    @Test
    @DisplayName("An application/octet-stream body binds to a byte[] param and reaches the resource (no 500)")
    void octetStreamBodyBindsToByteArrayParam(VertxTestContext ctx) {
        postBody(
                vertx,
                ctx,
                "/body/bytes",
                MediaType.APPLICATION_OCTET_STREAM,
                Buffer.buffer(new byte[] {1, 2, 3, 4, 5}),
                statusAndBody -> assertEquals("200|bytes=5", statusAndBody));
    }

    @Test
    @DisplayName("A JSON-array body binds to a List<Dto> param and reaches the resource (no 500)")
    void jsonArrayBodyBindsToListParam(VertxTestContext ctx) {
        postBody(
                vertx,
                ctx,
                "/body/list",
                MediaType.APPLICATION_JSON,
                Buffer.buffer("[{\"name\":\"a\"},{\"name\":\"b\"}]"),
                statusAndBody -> assertEquals("200|names=a,b", statusAndBody));
    }

    /**
     * Deploys the {@link BodyResource} under the {@code none} strategy with the text + binary + JSON
     * decoders wired, POSTs {@code body} with the given content type, and asserts on a
     * {@code status|responseBody} projection.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param path        the request path
     * @param contentType the request {@code Content-Type}
     * @param body        the raw request body buffer
     * @param assertion   the assertion on the {@code status|body} projection
     */
    private void postBody(
            Vertx vertx,
            VertxTestContext ctx,
            String path,
            String contentType,
            Buffer body,
            Consumer<String> assertion) {
        // Wire the text + binary decoders alongside JSON so the non-object body paths resolve a decoder
        // the way the production dispatch ITs do (default factory only wires the JSON decoder).
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .sortedDecoders(List.of(
                        new JsonRequestBodyDecoder(), new TextRequestBodyDecoder(), new BinaryRequestBodyDecoder()))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new BodyResource()));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    exchange(
                                    HttpMethod.POST,
                                    s.actualPort(),
                                    path,
                                    req -> req.putHeader("Content-Type", contentType),
                                    body)
                            .onComplete(ctx.succeeding(result -> {
                                ctx.verify(() -> withDiagnostic(
                                        result,
                                        () -> assertion.accept(result.statusCode() + "|"
                                                + result.body().toString())));
                                ctx.completeNow();
                            }));
                }));
    }

    @Test
    @DisplayName("The gate builds and stashes one BoundRequest the invoker reuses (body read once)")
    void bodyReadOnce(VertxTestContext ctx) {
        // A gate that materialises the BoundRequest (reads the body once) and stashes it; the invoker
        // must reuse that stash rather than re-reading. We assert the stashed instance identity is the
        // same one the invoker uses by recording the BoundRequest identity hash in both places.
        List<Integer> identities = new ArrayList<>();
        BodyStashingGateStrategy gate = new BodyStashingGateStrategy(identities);

        JaxRsConfig config =
                JaxRsConfig.builder().validationStrategy("body-stash").build();
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .validationStrategies(Set.of(gate))
                .jaxRsConfig(config)
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new EchoResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    exchange(
                                    HttpMethod.POST,
                                    s.actualPort(),
                                    "/echo",
                                    req -> req.putHeader("Content-Type", "application/json"),
                                    Buffer.buffer("{\"name\":\"Alice\"}"))
                            .onComplete(ctx.succeeding(result -> {
                                ctx.verify(() -> {
                                    // Body materialised correctly through the reused bound request.
                                    assertEquals("name=Alice", result.body().toString());
                                    // The gate recorded one identity; the invoker recorded the same one.
                                    assertEquals(2, identities.size(), "gate and invoker each record once");
                                    assertEquals(
                                            identities.get(0),
                                            identities.get(1),
                                            "the invoker must reuse the gate's stashed BoundRequest (one body read)");
                                });
                                ctx.completeNow();
                            }));
                }));
    }

    /**
     * A gate strategy that materialises and stashes a {@link dev.vertique.rest.jaxrs.request.DefaultBoundRequest}
     * (reading the body once) and records its identity, so the body-read-once test can assert the
     * invoker reuses the same instance.
     */
    static final class BodyStashingGateStrategy
            implements dev.vertique.rest.jaxrs.validation.RequestValidationStrategy {
        private final List<Integer> identities;

        BodyStashingGateStrategy(List<Integer> identities) {
            this.identities = identities;
        }

        @Override
        public String id() {
            return "body-stash";
        }

        @Override
        public Optional<io.vertx.core.Handler<RoutingContext>> gateFor(
                dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor op,
                dev.vertique.rest.jaxrs.validation.OperationSchemas schemas) {
            return Optional.of(ctx -> {
                dev.vertique.rest.jaxrs.request.BoundRequest bound =
                        new dev.vertique.rest.jaxrs.request.DefaultBoundRequest(ctx, op);
                ctx.put(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
                identities.add(System.identityHashCode(bound));
                // Record again from the invoker via a context hook: the invoker reads the same key.
                ctx.addEndHandler(v -> {
                    dev.vertique.rest.jaxrs.request.BoundRequest seen =
                            ctx.get(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST);
                    identities.add(System.identityHashCode(seen));
                });
                ctx.next();
            });
        }
    }

    // --- Order test: auth -> gate -> contributors -> invoker ---

    /** A contributor that records its label and continues, used to assert handler ordering. */
    static final class RecordingContributor implements OperationHandlerContributor {
        private final String label;
        private final int priority;
        private final List<String> log;

        RecordingContributor(String label, int priority, List<String> log) {
            this.label = label;
            this.priority = priority;
            this.log = log;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(ctx -> {
                log.add(label);
                ctx.next();
            });
        }

        @Override
        public String orderKey() {
            return label;
        }
    }

    @Test
    @DisplayName("Handlers run in order: auth -> gate -> contributors (identity@80, authz@100) -> invoker")
    void handlerExecutionOrderIsPreserved(VertxTestContext ctx) {
        List<String> log = new ArrayList<>();

        // A security scheme handler that records "auth" before the operation, applied via the
        // operation's effective security requirement on the resource (declared below).
        SchemeRecordingSecurityHandler authHandler = new SchemeRecordingSecurityHandler("bearerAuth", log);

        // A recording gate strategy that records "gate".
        RecordingGateStrategy gateStrategy = new RecordingGateStrategy(log);

        RecordingContributor identity = new RecordingContributor("identity", 80, log);
        RecordingContributor authz = new RecordingContributor("authz", 100, log);

        JaxRsConfig config =
                JaxRsConfig.builder().validationStrategy("recording-gate").build();

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .validationStrategies(Set.of(gateStrategy))
                .securitySchemeHandlers(Set.of(authHandler))
                .operationHandlerContributors(Set.of(identity, authz))
                .jaxRsConfig(config)
                .build();

        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new SecuredOrderResource(log)));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    exchange(HttpMethod.GET, s.actualPort(), "/secured", null, null)
                            .onComplete(ctx.succeeding(result -> {
                                ctx.verify(() -> assertEquals(
                                        List.of("auth", "gate", "identity", "authz", "invoker"),
                                        log,
                                        "handler order must be auth -> gate -> contributors -> invoker"));
                                ctx.completeNow();
                            }));
                }));
    }

    /** Resource with a {@code @SecurityRequirement} so the bearerAuth scheme applies. */
    @Path("/secured")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public static class SecuredOrderResource {
        private final List<String> log;

        SecuredOrderResource(List<String> log) {
            this.log = log;
        }

        /**
         * Records {@code "invoker"} then returns 200.
         *
         * @return a constant body
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "secured")
        public String handle() {
            log.add("invoker");
            return "ok";
        }
    }

    // --- Auth-handler ordering: secured + @Consumes on the same route must register cleanly ---

    /**
     * Resource declaring a class-level {@code @SecurityRequirement} AND a {@code @Consumes} POST.
     * Registering this exercises the route-handler add-order: the authentication handler must be added
     * before the {@code @Consumes} 415-check (a Vert.x {@code USER} handler), or Vert.x throws
     * {@code IllegalStateException: Cannot add [AUTHENTICATION] handler to route with [USER] handler}.
     */
    @Path("/secured-consumes")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public static class SecuredConsumesResource {

        /**
         * Accepts a JSON body on a secured route, so both an auth handler and a {@code @Consumes}
         * 415-check are installed on the same Vert.x route.
         *
         * @param payload the request body bean
         * @return the echoed name
         */
        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "securedConsumes")
        public String create(Payload payload) {
            return "name=" + payload.name;
        }
    }

    @Test
    @DisplayName("A secured op with @Consumes registers without the Vert.x AUTHENTICATION-after-USER error")
    void securedConsumesRouteRegistersWithoutOrderingError(VertxTestContext ctx) {
        // An auth handler that is a real Vert.x AuthenticationHandler, so Vert.x classifies the route's
        // first handler as AUTHENTICATION (matching production JwtBearerSecuritySchemeHandler). If the
        // @Consumes USER handler were added first, createRouter would fail with IllegalStateException.
        SchemeAuthenticationHandler authHandler = new SchemeAuthenticationHandler("bearerAuth");

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(authHandler))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new SecuredConsumesResource()));

        mount.createRouter(vertx).onComplete(ctx.succeeding(apiRouter -> {
            ctx.verify(() -> {
                // The router built without the AUTHENTICATION-after-USER IllegalStateException; the
                // route exists and the secured @Consumes operation registered cleanly.
                assertTrue(
                        apiRouter.getRoutes().stream().anyMatch(r -> "/secured-consumes".equals(r.getPath())),
                        "the secured @Consumes route must be registered");
            });
            ctx.completeNow();
        }));
    }

    /**
     * A {@link dev.vertique.rest.core.security.SecuritySchemeHandler} that registers a real Vert.x
     * {@link io.vertx.ext.web.handler.AuthenticationHandler}, so the route's first handler is
     * classified {@code AUTHENTICATION} — reproducing the production add-order constraint.
     */
    static final class SchemeAuthenticationHandler implements dev.vertique.rest.core.security.SecuritySchemeHandler {
        private final String scheme;

        SchemeAuthenticationHandler(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public void configure(dev.vertique.rest.core.routing.SecuritySchemeRegistry registry) {
            // A pass-through AuthenticationHandler: Vert.x tags it AUTHENTICATION by its interface type,
            // so the route's first handler is classified AUTHENTICATION exactly as in production.
            io.vertx.ext.web.handler.AuthenticationHandler auth = RoutingContext::next;
            registry.authenticationHandler(auth);
        }
    }

    // --- Route shadowing: a more-specific static route must not be shadowed by an earlier {name} route ---

    /**
     * Resource declaring a plain {@code GET /r/{name}} (unsecured) BEFORE a more-specific
     * {@code GET /r/secured} (secured by a {@code @SecurityRequirement}). Declared in this order to
     * reproduce the auth-bypass: under naive iteration-order registration the parameter route is added
     * first and Vert.x's first-match rule lets {@code /r/{name}} shadow {@code /r/secured}, so the
     * secured route's authentication handler never runs. The specificity sort must register
     * {@code /r/secured} first so the secured route wins the match.
     */
    @Path("/r")
    public static class ShadowingResource {

        /**
         * Unsecured parameter route, declared first so it would shadow {@code /r/secured} without the
         * specificity sort.
         *
         * @param name the path name
         * @return the bound name echoed back
         */
        @GET
        @Path("/{name}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "shadowGreet")
        public String greet(@PathParam("name") String name) {
            return "name=" + name;
        }

        /**
         * Secured static route guarded by the {@code bearerAuth} scheme.
         *
         * @return a constant body when authentication passes (it does not in the test)
         */
        @GET
        @Path("/secured")
        @Produces(MediaType.TEXT_PLAIN)
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "shadowSecured")
        public String secured() {
            return "secured";
        }
    }

    @Test
    @DisplayName("A static /r/secured route is not shadowed by an earlier /r/{name}: it hits the auth handler (401)")
    void staticRouteNotShadowedByEarlierParamRoute(VertxTestContext ctx) {
        // A real Vert.x AuthenticationHandler that rejects (401) when no credentials are present, so a
        // request reaching the secured route is rejected, while a request shadowed onto /r/{name} would
        // return 200. This directly distinguishes "secured route matched" from "auth bypassed".
        RejectingAuthenticationHandler authHandler = new RejectingAuthenticationHandler("bearerAuth");

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(authHandler))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ShadowingResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    // No Authorization header → the secured route's auth handler must reject with 401.
                    // If /r/{name} shadowed it, the response would be 200 "name=secured".
                    exchange(HttpMethod.GET, s.actualPort(), "/r/secured", null, null)
                            .map(result ->
                                    result.statusCode() + "|" + result.body().toString())
                            .onComplete(ctx.succeeding(statusAndBody -> {
                                ctx.verify(() -> assertTrue(
                                        statusAndBody.startsWith("401"),
                                        "/r/secured must hit the secured route's auth handler (401), not be"
                                                + " shadowed onto /r/{name} (was: " + statusAndBody + ")"));
                                // And the unsecured /r/{name} route still works for a non-shadowed name.
                                exchange(HttpMethod.GET, s.actualPort(), "/r/alice", null, null)
                                        .onComplete(ctx.succeeding(result -> {
                                            ctx.verify(() -> assertEquals(
                                                    "name=alice",
                                                    result.body().toString(),
                                                    "the plain /r/{name} route must still match a non-shadowed name"));
                                            ctx.completeNow();
                                        }));
                            }));
                }));
    }

    /**
     * A {@link dev.vertique.rest.core.security.SecuritySchemeHandler} registering a real Vert.x
     * {@link io.vertx.ext.web.handler.AuthenticationHandler} that rejects unauthenticated requests with
     * {@code 401}. Used to prove a secured route was actually matched (rather than shadowed onto an
     * unsecured parameter route).
     */
    static final class RejectingAuthenticationHandler implements dev.vertique.rest.core.security.SecuritySchemeHandler {
        private final String scheme;

        RejectingAuthenticationHandler(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public void configure(dev.vertique.rest.core.routing.SecuritySchemeRegistry registry) {
            io.vertx.ext.web.handler.AuthenticationHandler auth = ctx -> ctx.fail(401);
            registry.authenticationHandler(auth);
        }
    }

    // --- OR security: alternative @SecurityRequirements are enforced as OR, not AND ---

    /**
     * Resource declaring TWO alternative {@code @SecurityRequirement}s ({@code schemeA} and
     * {@code schemeB}). Per the OpenAPI spec the operation's security array is an OR: a request
     * satisfying EITHER scheme is authenticated. Before the fix the registrar chained both auth
     * handlers sequentially (a Vert.x AND), so a request satisfying only one alternative was rejected.
     */
    @Path("/or-secured")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeA")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeB")
    public static class OrSecuredResource {

        /**
         * Returns 200 once either alternative scheme authenticates the request.
         *
         * @return a constant body proving the request reached dispatch
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "orSecured")
        public String handle() {
            return "ok";
        }
    }

    /**
     * A {@link dev.vertique.rest.core.security.SecuritySchemeHandler} backed by a real Vert.x
     * {@link io.vertx.ext.web.handler.SimpleAuthenticationHandler} (an
     * {@code AuthenticationHandlerInternal}, as {@link io.vertx.ext.web.handler.ChainAuthHandler}
     * requires of its members). Its authentication function succeeds with a {@link io.vertx.ext.auth.User}
     * iff the request carries the configured {@code X-Credential} header value, and otherwise fails with
     * a {@code 401} {@link io.vertx.ext.web.handler.HttpException} — the signal a
     * {@code ChainAuthHandler.any()} uses to try the next alternative.
     */
    static final class CredentialAuthHandler implements dev.vertique.rest.core.security.SecuritySchemeHandler {
        private final String scheme;
        private final String acceptedCredential;

        CredentialAuthHandler(String scheme, String acceptedCredential) {
            this.scheme = scheme;
            this.acceptedCredential = acceptedCredential;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public void configure(dev.vertique.rest.core.routing.SecuritySchemeRegistry registry) {
            io.vertx.ext.web.handler.SimpleAuthenticationHandler auth =
                    io.vertx.ext.web.handler.SimpleAuthenticationHandler.create();
            auth.authenticate(routingContext -> {
                String credential = routingContext.request().getHeader("X-Credential");
                if (acceptedCredential.equals(credential)) {
                    return Future.succeededFuture(
                            io.vertx.ext.auth.User.create(new io.vertx.core.json.JsonObject().put("sub", scheme)));
                }
                // A 401 HttpException is the failure shape ChainAuthHandler.any() recognises as
                // "this alternative did not authenticate — try the next one".
                return Future.failedFuture(new io.vertx.ext.web.handler.HttpException(401));
            });
            registry.authenticationHandler(auth);
        }
    }

    @Test
    @DisplayName("Two alternative @SecurityRequirements are OR: either credential authorizes, neither is 401")
    void alternativeSecuritySchemesAreEnforcedAsOr(VertxTestContext ctx) {
        // schemeA accepts credential "A"; schemeB accepts credential "B". With OR semantics a request
        // carrying A's credential OR B's credential reaches dispatch (200); a request carrying neither
        // is rejected (401). Under the old AND chain the A-only and B-only requests would be rejected.
        CredentialAuthHandler schemeA = new CredentialAuthHandler("schemeA", "A");
        CredentialAuthHandler schemeB = new CredentialAuthHandler("schemeB", "B");

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(schemeA, schemeB))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new OrSecuredResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    int port = s.actualPort();
                    // Request satisfying schemeA only → authorized (200).
                    statusFor(port, "A")
                            .compose(statusA -> {
                                ctx.verify(() -> assertEquals(
                                        200, statusA, "a request satisfying schemeA alone must be authorized (OR)"));
                                // Request satisfying schemeB only → authorized (200).
                                return statusFor(port, "B");
                            })
                            .compose(statusB -> {
                                ctx.verify(() -> assertEquals(
                                        200, statusB, "a request satisfying schemeB alone must be authorized (OR)"));
                                // Request satisfying neither → unauthorized (401).
                                return statusFor(port, "none");
                            })
                            .onComplete(ctx.succeeding(statusNone -> {
                                ctx.verify(() -> assertEquals(
                                        401, statusNone, "a request satisfying neither scheme must be unauthorized"));
                                ctx.completeNow();
                            }));
                }));
    }

    /**
     * Issues a {@code GET /or-secured} carrying the given {@code X-Credential} header and resolves to
     * the response status code, draining the body so the connection is released.
     *
     * @param port       the bound server port
     * @param credential the {@code X-Credential} header value to send
     * @return a future of the response status code
     */
    private Future<Integer> statusFor(int port, String credential) {
        return exchange(HttpMethod.GET, port, "/or-secured", req -> req.putHeader("X-Credential", credential), null)
                .map(HttpResult::statusCode);
    }

    // --- Fail-fast on unknown strategy ---

    @Test
    @DisplayName("An unknown validationStrategy id fails router build with RestConfigurationException")
    void unknownStrategyFailsFast(VertxTestContext ctx) {
        JaxRsConfig config =
                JaxRsConfig.builder().validationStrategy("does-not-exist").build();
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().jaxRsConfig(config).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UserResource()));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> mount.createRouter(vertx),
                "selecting an unregistered strategy id must fail fast");
        assertTrue(ex.getMessage().contains("does-not-exist"));
        ctx.completeNow();
    }

    // --- Recording test doubles for the order test ---

    /** A {@link dev.vertique.rest.core.security.SecuritySchemeHandler} that records on the auth phase. */
    static final class SchemeRecordingSecurityHandler implements dev.vertique.rest.core.security.SecuritySchemeHandler {
        private final String scheme;
        private final List<String> log;

        SchemeRecordingSecurityHandler(String scheme, List<String> log) {
            this.scheme = scheme;
            this.log = log;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public void configure(dev.vertique.rest.core.routing.SecuritySchemeRegistry registry) {
            registry.authenticationHandler(ctx -> {
                log.add("auth");
                ctx.next();
            });
        }
    }

    /** A validation strategy whose gate records {@code "gate"} and continues. */
    static final class RecordingGateStrategy implements dev.vertique.rest.jaxrs.validation.RequestValidationStrategy {
        private final List<String> log;

        RecordingGateStrategy(List<String> log) {
            this.log = log;
        }

        @Override
        public String id() {
            return "recording-gate";
        }

        @Override
        public Optional<io.vertx.core.Handler<RoutingContext>> gateFor(
                dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor op,
                dev.vertique.rest.jaxrs.validation.OperationSchemas schemas) {
            return Optional.of(ctx -> {
                log.add("gate");
                ctx.next();
            });
        }
    }

    // --- HTTP helpers ---

    /**
     * Deploys a mount under the {@code none} strategy and runs a single GET, asserting on the body.
     *
     * @param vertx     the Vert.x instance
     * @param ctx       the test context
     * @param resources the resources to mount
     * @param method    the HTTP method
     * @param path      the request path
     * @param assertion the assertion on the response body string
     */
    private void request(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            HttpMethod method,
            String path,
            Consumer<String> assertion) {
        deploy(vertx, ctx, resources, port -> exchange(method, port, path, null, null)
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> withDiagnostic(
                            result, () -> assertion.accept(result.body().toString())));
                    ctx.completeNow();
                })));
    }

    /**
     * Deploys a mount under the {@code none} strategy and invokes {@code afterListen} with the bound
     * port once the server is up.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param resources   the resources to mount
     * @param afterListen the callback invoked with the server port
     */
    private void deploy(
            Vertx vertx, VertxTestContext ctx, Set<Object> resources, java.util.function.IntConsumer afterListen) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    afterListen.accept(s.actualPort());
                }));
    }

    /**
     * Performs one exchange against the shared {@link HttpClient} with the response continuation —
     * <em>including</em> the body read — attached before the send is initiated.
     *
     * <p>This ordering is load-bearing rather than stylistic. Vert.x discards response body buffers
     * delivered before a body handler is attached, so a {@code send()} followed by a body read in a
     * later {@code compose} step loses the entire body whenever the calling thread is descheduled in
     * between: {@code body()} then completes <em>successfully</em> with zero bytes under an otherwise
     * correct status. Attaching the continuation to {@link HttpClientRequest#response()} first, and
     * only then calling {@code end()}, removes that window. See {@code HttpClientBodyReadRaceIT}.
     *
     * <p>The future returned by {@code end()} is deliberately not composed into the result chain: a
     * response the server did send must not be masked by a write-side failure.
     *
     * @param method     the HTTP method
     * @param port       the bound server port
     * @param path       the request path
     * @param customizer applied to the request before the send is initiated, or {@code null} for none
     * @param body       the request body to send, or {@code null} to send no body
     * @return a future of the response status code and fully-read body
     */
    private static Future<HttpResult> exchange(
            HttpMethod method, int port, String path, Consumer<HttpClientRequest> customizer, Buffer body) {
        return client.request(method, port, "127.0.0.1", path).compose(request -> {
            Future<HttpResult> result = request.response().compose(response -> response.body()
                    .map(b -> new HttpResult(
                            response.statusCode(),
                            // Copied: the response's headers are not guaranteed to stay readable
                            // once the exchange is recycled.
                            MultiMap.caseInsensitiveMultiMap().addAll(response.headers()),
                            response.version(),
                            b)));
            if (customizer != null) {
                customizer.accept(request);
            }
            if (body == null) {
                request.end();
            } else {
                request.end(body);
            }
            return result;
        });
    }

    /**
     * One observed HTTP response: the status code plus the fully-read body.
     *
     * @param statusCode the response status code
     * @param body       the complete response body
     */
    /**
     * Runs {@code assertion}, re-throwing any failure with the response's wire evidence appended.
     *
     * <p>The flake this guards against reports only the mismatched value — an empty body under a
     * correct status — which is consistent with several unrelated causes. Appending
     * {@link HttpResult#diagnostic()} makes a single CI failure sufficient to tell them apart,
     * without changing what any assertion actually asserts.
     *
     * @param result    the response the assertion is about
     * @param assertion the assertion to run
     */
    private static void withDiagnostic(HttpResult result, Runnable assertion) {
        try {
            assertion.run();
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + " " + result.diagnostic(), failure);
        }
    }

    private record HttpResult(int statusCode, MultiMap headers, HttpVersion version, Buffer body) {

        /**
         * Describes the response as it came off the wire, for a failure whose cause is not local.
         *
         * <p>The decisive field is {@code marker}: our servers stamp {@link #SERVER_MARKER_HEADER}
         * on every response they write, so {@code marker=ABSENT} means the answer did not come from
         * our server at all, while a matching marker with an empty body means our own pipeline wrote
         * no entity. Those are unrelated defects and the failure text has so far been unable to
         * separate them. Content-Length and the HTTP version distinguish a body that was never
         * written from one that was announced and then lost.
         *
         * @return a single-line description of status, marker, framing headers and body length
         */
        String diagnostic() {
            String marker = headers.get(SERVER_MARKER_HEADER);
            return "[status=" + statusCode
                    + " marker=" + (marker == null ? "ABSENT" : (SERVER_ID.equals(marker) ? "ours" : marker))
                    + " httpVersion=" + version
                    + " contentLength=" + headers.get("Content-Length")
                    + " transferEncoding=" + headers.get("Transfer-Encoding")
                    + " connection=" + headers.get("Connection")
                    + " contentType=" + headers.get("Content-Type")
                    + " bodyLength=" + body.length()
                    + " headers=" + headers.entries() + "]";
        }
    }
}
