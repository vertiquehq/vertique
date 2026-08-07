// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression tests proving the profile mapper owns the request-body FIRST PARSE even when the default
 * {@code web-validation} gate is active (FR-JSON-024/024A).
 *
 * <p>The Critical defect these guard against: under {@link WebValidationStrategy}, the gate's
 * {@code validateBody} constructs and stashes the per-request {@code BoundRequest} <em>before</em> the
 * terminal invoker runs. If the resolved profile mapper is stashed only by the invoker, the gate's
 * earlier bind has already first-parsed the body with the default Vert.x path, so the profile's strict
 * parser features ({@code STRICT_DUPLICATE_DETECTION}, {@code FAIL_ON_TRAILING_TOKENS}) never run on a
 * route that carries a body schema — exactly the common POST/PUT case. The fix stashes the resolved
 * mapper ahead of the gate, so the profile mapper performs the first parse regardless of the gate.
 *
 * <p>Every test here mounts a resource whose body bean carries a {@code @Schema} constraint, so the
 * gate's {@code bodyValidator} is non-null and {@code validateBody} actually binds the body — that is
 * what activates the gate's early bind. The resource selects the strict {@code strict-test} profile via
 * a class-level {@code @JsonProfile}, so a duplicate key / trailing token / BOM-prefixed body must be
 * rejected with a {@code 400} produced by the profile mapper's first parse, not silently accepted by
 * the default Vert.x path.
 *
 * <p>Built through {@link MountFixtures} over {@link ValidationMountComponent} (the {@code
 * vertique-rest-test} fixture), so every deployed mount carries the full set of production middlewares
 * — including {@code ContentTypeValidationMiddleware}, which 415s a POST/PUT/PATCH body whose {@code
 * Content-Type} is missing or unsupported before the request ever reaches the router-level binder. The
 * no-{@code Content-Type} variant of this suite's original scenario is therefore unreachable end-to-end
 * against a production-faithful mount; the equivalent binding-level coverage now lives in {@code
 * dev.vertique.rest.jaxrs.request.NoContentTypeProfiledBodyBindingTest} (module {@code
 * vertique-rest-jaxrs}), which exercises {@code DefaultBoundRequest.bindBody}'s missing-content-type
 * branch directly, with no HTTP and no middleware in the path.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledBodyParseUnderGateIT {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Strict profile fixture ---

    /**
     * Builds the {@code strict-test} profile: a Jackson mapper with Vert.x JSON support (required by the
     * registry probe and so {@code JsonObject} round-trips) plus the two strict parser features the
     * tests exercise — {@code STRICT_DUPLICATE_DETECTION} (duplicate keys) and
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

    // --- Resource fixtures ---

    /**
     * Body bean carrying a {@code name} string with a {@code @Schema(minLength = 1)} constraint, so the
     * gate synthesises a body schema and installs a {@code bodyValidator} — which is what makes the gate
     * bind (and thus first-parse) the body before the invoker runs.
     */
    public static class Payload {
        @Schema(minLength = 1)
        public String name;
    }

    /**
     * Resource selecting the {@code strict-test} profile at the class level, with a body bean that
     * carries a schema constraint so the {@code web-validation} gate binds the body before dispatch.
     */
    @Path("/strict")
    @JsonProfile("strict-test")
    public static class StrictResource {

        /**
         * Echoes the body's {@code name}; a body the strict profile rejects (duplicate key, trailing
         * token) must surface as 400 from the profile mapper's first parse, even though the gate runs
         * first.
         *
         * @param payload the request body bean, first-parsed by the strict profile mapper
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictGatedEcho")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }
    }

    /**
     * Resource with NO {@code @JsonProfile} but the same schema-constrained body bean, so the
     * {@code web-validation} gate is active but the effective profile is {@code vertx}. A normal body
     * must dispatch to 200, proving the gate-active path is unchanged for the vertx default.
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
        @Operation(operationId = "plainGatedEcho")
        public String echo(Payload payload) {
            return "name=" + payload.name;
        }
    }

    // --- Test: duplicate keys rejected 400 with the validation gate active ---

    @Test
    @DisplayName("A duplicate JSON key on a strict-profiled gated body is rejected with 400")
    void duplicateKeys_rejected400_withValidationGateActive(Vertx vertx, VertxTestContext ctx) {
        // The body schema (minLength on name) makes the gate's validateBody bind the body before the
        // invoker. STRICT_DUPLICATE_DETECTION on the profile mapper must still reject the duplicate
        // "name" key during the FIRST PARSE → 400 (not 200 last-wins, not 500).
        postStrict(
                vertx,
                ctx,
                Buffer.buffer("{\"name\":\"a\",\"name\":\"b\"}"),
                status -> assertEquals(
                        400, status, "a duplicate JSON key must be rejected with 400 even under the gate"));
    }

    // --- Test: trailing tokens rejected 400 with the validation gate active ---

    @Test
    @DisplayName("Trailing tokens after a strict-profiled gated body are rejected with 400")
    void trailingTokens_rejected400_withValidationGateActive(Vertx vertx, VertxTestContext ctx) {
        // FAIL_ON_TRAILING_TOKENS on the profile mapper must reject the extra "{}" after the object
        // during the FIRST PARSE, even with the gate active → 400.
        postStrict(
                vertx,
                ctx,
                Buffer.buffer("{\"name\":\"a\"}{}"),
                status -> assertEquals(400, status, "trailing tokens must be rejected with 400 even under the gate"));
    }

    // --- Test: BOM-prefixed body is profile-parsed (BOM-tolerant shape detection) ---

    @Test
    @DisplayName("A BOM-prefixed duplicate-key body on a profiled gated route is rejected with 400")
    void bomPrefixedBody_profiledStrictParse(Vertx vertx, VertxTestContext ctx) {
        // A leading UTF-8 BOM (EF BB BF) must be skipped during shape detection so the '{' is seen and
        // the body is routed to the profile mapper's strict first parse — the duplicate key is then
        // rejected with 400. Without BOM-skipping the body would be misclassified as scalar and bypass
        // the strict parse.
        Buffer bom = Buffer.buffer(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        Buffer body = Buffer.buffer().appendBuffer(bom).appendString("{\"name\":\"a\",\"name\":\"b\"}");
        postStrict(
                vertx,
                ctx,
                body,
                status -> assertEquals(400, status, "a BOM-prefixed duplicate-key body must be rejected with 400"));
    }

    // --- Test: vertx route under the gate accepts a normal body unchanged ---

    @Test
    @DisplayName("The vertx (default) profile under the gate accepts a normal body unchanged (2xx)")
    void vertxRoute_withGate_unchanged(Vertx vertx, VertxTestContext ctx) {
        // The /plain resource has the same body schema (gate active) but no @JsonProfile, so the
        // effective profile is vertx and the body is parsed by today's default path — a normal body must
        // dispatch to 200, proving the gate-active vertx path is unchanged.
        deploy(vertx, ctx, Set.of(new PlainResource()), (port, c) -> c.request(
                        HttpMethod.POST, port, "127.0.0.1", "/plain")
                .compose(req ->
                        req.putHeader("Content-Type", "application/json").send(Buffer.buffer("{\"name\":\"alice\"}")))
                .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> assertEquals(
                            "200|name=alice", result, "the gate-active vertx path must accept a normal body"));
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * POSTs {@code body} as {@code application/json} to the {@code strict-test}-profiled {@code /strict}
     * resource (gate active) and asserts on the status code.
     *
     * @param vertx     the Vert.x instance
     * @param ctx       the test context
     * @param body      the raw request body buffer
     * @param assertion the assertion on the response status code
     */
    private void postStrict(Vertx vertx, VertxTestContext ctx, Buffer body, IntConsumer assertion) {
        deploy(vertx, ctx, Set.of(new StrictResource()), (port, c) -> c.request(
                        HttpMethod.POST, port, "127.0.0.1", "/strict")
                .compose(
                        req -> req.putHeader("Content-Type", "application/json").send(body))
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .onComplete(ctx.succeeding(status -> {
                    ctx.verify(() -> assertion.accept(status));
                    ctx.completeNow();
                })));
    }

    /**
     * Deploys the given resources through the real {@code web-validation} gate (via {@link
     * ValidationMountComponent}, which wires the real injected {@link WebValidationStrategy} and {@link
     * AnnotationSchemaSource}) with a profile registry carrying the {@code strict-test} profile, starts
     * an HTTP server, and invokes {@code afterListen} with the bound port and a shared {@link
     * HttpClient}.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param resources   the JAX-RS resources to mount
     * @param afterListen callback invoked with the server port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx, VertxTestContext ctx, Set<Object> resources, BiConsumer<Integer, HttpClient> afterListen) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addJsonMapperProfile(strictTestProfile())
                .build();
        RestTestMounts.startServer(vertx, MountFixtures.mount(vertx, contributions), resources)
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    afterListen.accept(s.actualPort(), client);
                }));
    }
}
