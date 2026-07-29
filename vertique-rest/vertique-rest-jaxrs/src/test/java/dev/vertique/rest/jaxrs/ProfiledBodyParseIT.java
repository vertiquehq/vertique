// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for profile-aware request-body first parse (FR-JSON-024/024A, slice 2.2).
 *
 * <p>When a resource method selects a non-{@code vertx} JSON profile (here via a class-level
 * {@code @JsonProfile("strict-test")}), {@code DefaultBoundRequest} must perform the FIRST PARSE of
 * the raw request body with that profile's {@link ObjectMapper}, applying the profile's strict
 * parser features. A body the strict profile rejects (a duplicate JSON key, or trailing tokens after
 * the value) must surface as an HTTP {@code 400}, produced through the framework's standard error
 * pipeline ({@code ValidationException} &rarr; {@code DefaultExceptionMapper} &rarr; 400).
 *
 * <p>The {@code vertx}-profile path (no {@code @JsonProfile}) must be byte-for-byte unchanged: a
 * normal JSON body is accepted and dispatched exactly as today, proving the strict-parse change is
 * scoped to profiled boundaries only.
 *
 * <p>The harness mirrors {@link ConsumesEnforcementIT}: a {@link JaxRsRouterMount} built via
 * {@link TestFactories} under the default {@code none} validation strategy, wired with a
 * {@link DefaultJsonMapperProfileRegistry} that carries the {@code strict-test} profile so the
 * per-method resolver can resolve it at router-build time.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledBodyParseIT {

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static Vertx vertx;
    private static HttpClient client;

    // --- Per-test resources ---

    private HttpServer server;

    // --- Setup / teardown ---

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

    // --- Strict profile fixture ---

    /**
     * Builds the {@code strict-test} profile: a Jackson mapper with Vert.x JSON support (required by
     * the registry probe, and so {@code JsonObject} round-trips) plus the two strict parser features
     * the tests exercise — {@code STRICT_DUPLICATE_DETECTION} (duplicate keys) and
     * {@code FAIL_ON_TRAILING_TOKENS} (trailing garbage after the value).
     *
     * @return the {@code strict-test} profile
     */
    private static JsonMapperProfile strictTestProfile() {
        ObjectMapper strictMapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .addModule(VertxJsonSupport.module())
                .build();
        return JsonMapperProfiles.of(JsonProfileId.of("strict-test"), strictMapper);
    }

    /**
     * Builds the {@code no-coercion} profile: a Jackson mapper with Vert.x JSON support plus
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled and {@code ALLOW_COERCION_OF_SCALARS} disabled. Both
     * are MATERIALIZATION-time features — they fire when the intermediate JSON is bound to the DTO, not
     * at the first parse (slice 2.3, FR-JSON-024B/022/023).
     *
     * <p>The load-bearing divergence is {@code ALLOW_COERCION_OF_SCALARS}: the Vert.x global mapper
     * leaves it ENABLED (verified), so it binds a string {@code "5"} into an {@code int} field; this
     * profile DISABLES it, so the same body is rejected at {@code convertValue}. A 400 on a profiled
     * route where the {@code /plain} vertx route returns 200 for the identical body therefore proves
     * the PROFILE mapper — not vertx — performed the materialization.
     *
     * @return the {@code no-coercion} profile
     */
    private static JsonMapperProfile noCoercionProfile() {
        ObjectMapper strictMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addModule(VertxJsonSupport.module())
                .build();
        return JsonMapperProfiles.of(JsonProfileId.of("no-coercion"), strictMapper);
    }

    // --- Resource fixtures ---

    /**
     * Body DTO carrying a {@code name} string and a {@code count} primitive {@code int}. The primitive
     * {@code int} is what makes scalar coercion observable: a JSON string {@code "5"} for {@code count}
     * binds under the coercion-enabled vertx mapper but is rejected by the {@code no-coercion} profile.
     */
    public static class Payload {
        public String name;
        public int count;
    }

    /**
     * Resource selecting the {@code strict-test} profile at the class level, so its body parameter is
     * first-parsed by the strict profile mapper.
     */
    @Path("/strict")
    @dev.vertique.core.json.JsonProfile("strict-test")
    public static class StrictResource {

        /**
         * Echoes the body's {@code name}, so a successful parse returns 200 and a rejected body
         * surfaces as 400 before the method runs.
         *
         * @param payload the request body bean, first-parsed by the strict profile mapper
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictEcho")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }
    }

    /**
     * Resource with NO {@code @JsonProfile} — the effective profile is {@code vertx}, so the body is
     * parsed by today's default Vert.x path (no strict features), proving the vertx path is unchanged.
     */
    @Path("/plain")
    public static class PlainResource {

        /**
         * Echoes the body's {@code name} for {@code POST /plain}, parsed by the default vertx path.
         *
         * @param payload the request body bean
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "plainEcho")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }
    }

    /**
     * Resource selecting the {@code no-coercion} profile, whose endpoints prove the MATERIALIZATION
     * sites (slice 2.3): the profile mapper — with scalar coercion DISABLED — performs the POJO and
     * collection binding. A JSON string for the primitive {@code count} is rejected by the profile
     * (400) where the vertx route binds the same body (200).
     */
    @Path("/bind")
    @dev.vertique.core.json.JsonProfile("no-coercion")
    public static class NoCoercionResource {

        /**
         * Echoes the body's {@code name}; a string value for the primitive {@code count} is rejected at
         * POJO materialization by the {@code no-coercion} profile mapper (FR-JSON-024B), surfacing as a
         * 400 before the method runs. The vertx mapper would coerce the string and return 200.
         *
         * @param payload the request body bean, materialized by the {@code no-coercion} profile mapper
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "noCoercionEcho")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }

        /**
         * Echoes the size of a {@code List<Payload>} body; a string value for an element's primitive
         * {@code count} is rejected at collection materialization by the {@code no-coercion} profile
         * mapper (FR-JSON-022/023), surfacing as a 400 before the method runs. Proves the
         * collection/array binding site routes through the profile mapper.
         *
         * @param payloads the request body list, materialized by the {@code no-coercion} profile mapper
         * @return the element count
         */
        @POST
        @Path("/list")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "noCoercionListEcho")
        public String echoList(List<Payload> payloads) {
            return "count=" + payloads.size();
        }
    }

    // --- Test 1: strict profile rejects duplicate keys with 400 ---

    @Test
    @DisplayName("A duplicate JSON key on a strict-profiled body is rejected with 400")
    void strictProfile_rejectsDuplicateKeys_returns400(VertxTestContext ctx) {
        // STRICT_DUPLICATE_DETECTION makes the profile mapper reject the duplicate "name" key during
        // the FIRST PARSE in DefaultBoundRequest, which must surface as a 400 — not a 200 (last-wins,
        // as the lenient vertx path would do) and not a 500.
        postStrict(
                vertx,
                ctx,
                Buffer.buffer("{\"name\":\"a\",\"name\":\"b\"}"),
                status -> assertEquals(400, status, "a duplicate JSON key must be rejected with 400"));
    }

    // --- Test 2: strict profile rejects trailing tokens with 400 ---

    @Test
    @DisplayName("Trailing tokens after a strict-profiled body are rejected with 400")
    void strictProfile_rejectsTrailingTokens_returns400(VertxTestContext ctx) {
        // FAIL_ON_TRAILING_TOKENS makes the profile mapper reject the extra "{}" after the object
        // during the FIRST PARSE, which must surface as a 400.
        postStrict(
                vertx,
                ctx,
                Buffer.buffer("{\"name\":\"a\"}{}"),
                status -> assertEquals(400, status, "trailing tokens must be rejected with 400"));
    }

    // --- Test 3: vertx profile accepts a normal body unchanged ---

    @Test
    @DisplayName("The vertx (default) profile accepts a normal body unchanged (2xx)")
    void vertxProfile_acceptsBody_unchanged(VertxTestContext ctx) {
        // The /plain resource has no @JsonProfile, so the effective profile is vertx and the body is
        // parsed by today's default path — a normal body must dispatch to 200, proving the strict
        // parse change does not touch the vertx path.
        deploy(vertx, ctx, Set.of(new PlainResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "127.0.0.1", "/plain")
                    .compose(req -> req.putHeader("Content-Type", "application/json")
                            .send(Buffer.buffer("{\"name\":\"alice\"}")))
                    .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() ->
                                assertEquals("200|name=alice", result, "the vertx path must accept a normal body"));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 4: no-coercion profile rejects a coerced POJO scalar at 400 (POJO materialization) ---

    @Test
    @DisplayName("A coerced scalar on a no-coercion-profiled POJO body is rejected with 400")
    void strictProfile_rejectsCoercedScalar_returns400(VertxTestContext ctx) {
        // ALLOW_COERCION_OF_SCALARS is a MATERIALIZATION feature: the body parses fine, but binding the
        // string "5" into the primitive int `count` must throw on the no-coercion PROFILE mapper at
        // convertValue and surface as 400. The vertx mapper (coercion enabled, verified) coerces "5" and
        // returns 200 on the identical body (see vertxProfile_coercedScalar_acceptedAsToday), so a 400
        // here proves the PROFILE mapper did the bind (slice 2.3 POJO site, FR-JSON-024B).
        deploy(
                vertx,
                ctx,
                Set.of(new NoCoercionResource()),
                (port, c) -> postJson(
                        ctx,
                        c,
                        port,
                        "/bind",
                        Buffer.buffer("{\"name\":\"a\",\"count\":\"5\"}"),
                        status -> assertEquals(
                                400, status, "a coerced scalar must be rejected with 400 under the profile")));
    }

    // --- Test 5: no-coercion profile rejects a coerced List-element scalar at 400 (collection site) ---

    @Test
    @DisplayName("A coerced scalar on a no-coercion-profiled List element is rejected with 400")
    void strictProfile_listBody_usesProfileMapper(VertxTestContext ctx) {
        // The List<Payload> body materialization must route through the profile mapper too: a string "5"
        // for an element's primitive int `count` must be rejected with 400, proving the collection/array
        // binding site (not just the POJO site) uses the profile mapper (slice 2.3 collection site,
        // FR-JSON-022/023). The vertx path would coerce and return 200.
        deploy(
                vertx,
                ctx,
                Set.of(new NoCoercionResource()),
                (port, c) -> postJson(
                        ctx,
                        c,
                        port,
                        "/bind/list",
                        Buffer.buffer("[{\"name\":\"a\",\"count\":\"5\"}]"),
                        status ->
                                assertEquals(400, status, "a coerced List-element scalar must be rejected with 400")));
    }

    // --- Test 6: vertx profile coerces the same scalar exactly as today (2xx) ---

    @Test
    @DisplayName("The vertx (default) profile coerces a string scalar exactly as today (2xx)")
    void vertxProfile_coercedScalar_acceptedAsToday(VertxTestContext ctx) {
        // Same body on the no-profile /plain resource: the effective profile is vertx, whose global
        // mapper coerces the string "5" into the primitive int `count` (verified default), so the body
        // binds and dispatches to 200 — proving the materialization change is scoped to profiled
        // boundaries only (vertx byte-for-byte unchanged).
        deploy(
                vertx,
                ctx,
                Set.of(new PlainResource()),
                (port, c) -> postJson(
                        ctx,
                        c,
                        port,
                        "/plain",
                        Buffer.buffer("{\"name\":\"alice\",\"count\":\"5\"}"),
                        status -> assertEquals(200, status, "the vertx path must coerce the string scalar")));
    }

    // --- Helpers ---

    /**
     * POSTs {@code body} as {@code application/json} to the {@code strict-test}-profiled
     * {@code /strict} resource and asserts on the response status code.
     *
     * @param vertx     the Vert.x instance
     * @param ctx       the test context
     * @param body      the raw request body buffer
     * @param assertion the assertion on the response status code
     */
    private void postStrict(Vertx vertx, VertxTestContext ctx, Buffer body, java.util.function.IntConsumer assertion) {
        deploy(
                vertx,
                ctx,
                Set.of(new StrictResource()),
                (port, c) -> postJson(ctx, c, port, "/strict", body, assertion));
    }

    /**
     * POSTs {@code body} as {@code application/json} to {@code path} on the already-deployed server and
     * asserts on the response status code, then completes the test context.
     *
     * @param ctx       the test context
     * @param client    the shared HTTP client
     * @param port      the bound server port
     * @param path      the request path
     * @param body      the raw request body buffer
     * @param assertion the assertion on the response status code
     */
    private static void postJson(
            VertxTestContext ctx,
            HttpClient client,
            int port,
            String path,
            Buffer body,
            java.util.function.IntConsumer assertion) {
        client.request(HttpMethod.POST, port, "127.0.0.1", path)
                .compose(
                        req -> req.putHeader("Content-Type", "application/json").send(body))
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .onComplete(ctx.succeeding(status -> {
                    ctx.verify(() -> assertion.accept(status));
                    ctx.completeNow();
                }));
    }

    /**
     * Deploys the given resources under the default {@code none} validation strategy with a profile
     * registry carrying the {@code strict-test} profile, starts an HTTP server, and invokes
     * {@code afterListen} with the bound port and the shared {@link HttpClient}.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param resources   the JAX-RS resources to mount
     * @param afterListen callback invoked with the server port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            java.util.function.BiConsumer<Integer, HttpClient> afterListen) {
        DefaultJsonMapperProfileRegistry registry =
                new DefaultJsonMapperProfileRegistry(Set.of(strictTestProfile(), noCoercionProfile()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().jsonMapperProfileRegistry(registry).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    afterListen.accept(s.actualPort(), client);
                }));
    }
}
