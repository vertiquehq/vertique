// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * RED integration test for Phase 3 Slice 3.2 (FR-JSON-058 / FR-JSON-058A / FR-JSON-059): JAX-RS
 * <em>error</em> / {@code ProblemDetail} response bodies must serialize through the effective profile —
 * the per-method resolved mapper for a matched method, and the boundary+global default
 * ({@code jaxrs.jsonProfile} &rarr; {@code json.jsonProfile} &rarr; the {@code vertique} floor) for
 * errors raised <em>before</em> any method matched. A profile-mapper throw while serializing an error
 * body must <strong>fail open</strong> to the process JSON codec ({@code Json.encode}), preserving the
 * mapped status code and the {@code application/problem+json} media type — never collapsing to a bare
 * 500 that drops the mapped status.
 *
 * <p>The encoder under test is the <strong>production</strong> {@code JsonBodyEncoder}, reached through
 * {@link ValidationMountComponent} (the {@code vertique-rest-test} fixture), so the RED&rarr;green
 * transition is genuinely driven by the production error-serialization path — not a re-implemented stub.
 *
 * <p><strong>Observable technique (probe-safe, mirrors {@code ProfiledResponseSerializationIT}).</strong>
 * Each error body is a plain {@link ErrorEntity} POJO carrying a present {@code name} and a {@code null}
 * {@code missing} field, with <em>no</em> {@code @JsonInclude} of its own — so the process codec
 * ({@code Json.encode}) renders {@code "missing":null} while the opinionated profile (a
 * {@code NON_NULL} mix-in scoped to {@link ErrorEntity}) omits it. {@code ErrorEntity} (not the
 * framework {@code ProblemDetail}) is used precisely because {@code ProblemDetail} already carries
 * {@code @JsonInclude(NON_NULL)}, which would make the two indistinguishable. The {@code NON_NULL}
 * difference survives the registry's structural round-trip probe because the probe round-trips a
 * null-bearing {@code JsonObject}, which the entity-scoped mix-in never touches. {@code java.time} is
 * deliberately avoided: the raw Vert.x mapper this harness runs as its process codec lacks jsr310 and
 * would throw on a date, turning the assertion into a hang rather than a clean signal.
 *
 * <p><strong>What each test pins:</strong>
 * <ul>
 *   <li><em>error in profiled method</em> — the matched route stashes the profile mapper before dispatch
 *       (slice 3.1) and the error body reads it.</li>
 *   <li><em>no-method error uses boundary+global default</em> — the no-method failure path resolves and
 *       stashes the boundary default before serializing (slice 3.2).</li>
 *   <li><em>fail-open on profile-mapper throw</em> — the throwing profile mapper's serialization failure
 *       is caught and the response completed through the process codec, mapped status and media type
 *       preserved, instead of leaving the client hanging on a never-completed response.</li>
 *   <li><em>unannotated error path</em> — no profile and no default means the {@code vertique} floor, so
 *       the error body carries the floor's opinions (the null field omitted).</li>
 *   <li><em>explicit {@code system} routes</em> — with the graph's {@code system} mapper installed as the
 *       process codec (as a booted application installs it), the resolver's identity sentinel fires,
 *       nothing is stashed, and the error body is the process codec's own rendering (null present) even
 *       under a different boundary default.</li>
 * </ul>
 *
 * <p><strong>Why a {@link WebClient} and not a raw {@code HttpClient}.</strong> A raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load {@code body()} can succeed with zero bytes while the status code is correct (issue #167).
 * Every assertion in this class is about the <em>bytes of the error body</em> — whether the null field
 * is present or omitted, and in two tests a byte-for-byte comparison against the process codec's own
 * rendering — so
 * a silently emptied body would report a profile-selection defect that did not happen. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send, so
 * the race is closed by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledErrorResponseIT {

    private static final String OPINIONATED_PROFILE = "error-profile";
    private static final String THROWING_PROFILE = "throwing-error-profile";
    private static final String SYSTEM_PROFILE = "system";

    private HttpServer server;
    private WebClient client;

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used for async teardown assertion
     */
    /**
     * Restores the raw Vert.x delegate as the process JSON codec's mapper after every test.
     *
     * <p>Only the explicit-{@code system} tests install one, but the reset is unconditional: a leaked
     * installation would make the next {@code install} of a different profile id throw, and this module
     * runs one fork for the whole class. The seam is opened by the module's failsafe
     * {@code -Dvertique.json.codec.allowReset=true} argLine.
     */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Profile fixtures ---

    /**
     * Builds the opinionated {@code error-profile}: a Jackson mapper with Vert.x JSON support (so the
     * registry probe's {@code JsonObject} round-trips), the jsr310 module with
     * {@code WRITE_DATES_AS_TIMESTAMPS} disabled (parity with the framework {@code vertique} profile),
     * and a {@code NON_NULL} mix-in scoped to {@link ErrorEntity} so the entity's null field is omitted
     * while the structural probe's null-bearing {@code JsonObject} is unaffected. The null-omission is
     * the observable difference from the process codec's own {@code Json.encode} rendering.
     *
     * @return the {@code error-profile} profile
     */
    private static JsonMapperProfile opinionatedErrorProfile() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(VertxJsonSupport.module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mapper.addMixIn(ErrorEntity.class, NonNullMixin.class);
        return JsonMapperProfiles.of(JsonProfileId.of(OPINIONATED_PROFILE), mapper);
    }

    /**
     * Builds the {@code throwing-error-profile}: a Jackson mapper with Vert.x JSON support (so the
     * registry probe's {@code JsonObject} round-trips cleanly and the profile constructs) plus a
     * serializer scoped to {@link ErrorEntity} that <strong>throws</strong> when serializing the entity.
     * The throw is scoped to {@link ErrorEntity}, so the registry's structural probe (which round-trips a
     * {@code JsonObject}, never an {@code ErrorEntity}) is unaffected and the profile is registrable. It
     * is used by the fail-open test: serializing an error body with this mapper throws, and the framework
     * must fall back to the process codec while preserving the mapped status + media type.
     *
     * @return the {@code throwing-error-profile} profile
     */
    private static JsonMapperProfile throwingErrorProfile() {
        SimpleModule throwing = new SimpleModule();
        throwing.addSerializer(ErrorEntity.class, new ThrowingErrorEntitySerializer());
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(VertxJsonSupport.module())
                .addModule(throwing)
                .build();
        return JsonMapperProfiles.of(JsonProfileId.of(THROWING_PROFILE), mapper);
    }

    /** Mix-in applying {@code NON_NULL} inclusion to the error entity only (not the probe samples). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class NonNullMixin {}

    /** A {@link JsonSerializer} for {@link ErrorEntity} that always throws — drives the fail-open path. */
    static final class ThrowingErrorEntitySerializer extends JsonSerializer<ErrorEntity> {
        @Override
        public void serialize(ErrorEntity value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            throw new IOException("profile mapper intentionally fails to serialize the error entity");
        }
    }

    // --- Entity + exception fixtures ---

    /**
     * Error body entity carrying a present {@code name} and a {@code null} {@code missing} field, with no
     * {@code @JsonInclude} of its own. Under the opinionated profile (via the {@code NON_NULL} mix-in) the
     * null field is omitted; under the process codec's {@code Json.encode} the null is included. Used in
     * place of the framework {@code ProblemDetail} (which is already {@code NON_NULL}) so the
     * profile-versus-process-codec difference is observable.
     */
    public static class ErrorEntity {
        public String name;
        public String missing;

        /** Creates an error entity with {@code name="boom"} and a {@code null} {@code missing} field. */
        public static ErrorEntity boom() {
            ErrorEntity e = new ErrorEntity();
            e.name = "boom";
            e.missing = null;
            return e;
        }
    }

    /** Exception raised by the profiled resource method; mapped to a 422 {@link ErrorEntity} error body. */
    static final class MappedFailure extends RuntimeException {
        MappedFailure() {
            super("mapped failure");
        }
    }

    /** Exception raised by the pre-routing middleware; mapped to a 404 {@link ErrorEntity} error body. */
    static final class NoMethodFailure extends RuntimeException {
        NoMethodFailure() {
            super("no matched method");
        }
    }

    // --- Resource fixtures ---

    /**
     * Resource selecting the opinionated {@code error-profile} at the class level whose GET throws
     * {@link MappedFailure}. The matched route stashes the profile mapper before dispatch (slice 3.1), so
     * the mapped {@link ErrorEntity} error body must serialize via the profile (null field omitted).
     */
    @Path("/profiled-error")
    @JsonProfile(OPINIONATED_PROFILE)
    public static class ProfiledErrorResource {

        /**
         * Always throws {@link MappedFailure}.
         *
         * @return never returns normally
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "profiledError")
        public Response get() {
            throw new MappedFailure();
        }
    }

    /**
     * Resource selecting the throwing {@code throwing-error-profile} at the class level whose GET throws
     * {@link MappedFailure}. The matched route stashes the throwing profile mapper, so serializing the
     * mapped {@link ErrorEntity} error body throws — exercising the fail-open path.
     */
    @Path("/throwing-error")
    @JsonProfile(THROWING_PROFILE)
    public static class ThrowingErrorResource {

        /**
         * Always throws {@link MappedFailure}.
         *
         * @return never returns normally
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "throwingError")
        public Response get() {
            throw new MappedFailure();
        }
    }

    /**
     * Resource with NO {@code @JsonProfile} whose GET throws {@link MappedFailure}; used by the
     * floor-default error test. With no profile and no configured default the effective profile is the
     * {@code vertique} floor, whose opinions omit the null field from the error body.
     */
    @Path("/plain-error")
    public static class PlainErrorResource {

        /**
         * Always throws {@link MappedFailure}.
         *
         * @return never returns normally
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "plainError")
        public Response get() {
            throw new MappedFailure();
        }
    }

    /**
     * Resource with an EXPLICIT {@code @JsonProfile("system")} and a {@code @Consumes("application/json")},
     * used by the 415-on-explicit-{@code system} regression test. The 415 check fires <em>before</em> the
     * resource method runs and — because the graph's {@code system} mapper is installed as the process
     * codec, so the resolver's identity sentinel fires — before (and instead of) any request-side mapper
     * stash. Under a boundary default naming another profile ({@code jaxrs.jsonProfile}), the 415 error
     * body must still be the process codec's own rendering (null field PRESENT) — reflecting the route's
     * explicit decision, NOT the boundary default.
     */
    @Path("/vertx-consumes")
    @JsonProfile(SYSTEM_PROFILE)
    public static class VertxConsumesResource {

        /**
         * Never invoked under the 415 test (the {@code @Consumes} check rejects the mismatched type first).
         *
         * @param body the (never-read) request body
         * @return never returns normally under the 415 test
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "vertxConsumes")
        public Response post(String body) {
            return Response.ok(ErrorEntity.boom()).build();
        }
    }

    /**
     * Resource with a class-level {@code @JsonProfile("error-profile")} (opinionated) and a
     * {@code @Consumes("application/json")}, used by the 415-on-profiled-route regression test. The 415
     * check fires <em>before</em> the request-side mapper stash (which {@code JaxRsRouteRegistrar}
     * installs after the {@code @Consumes} check), so a naive failure handler would not see the profile
     * mapper. The 415 error body must nonetheless serialize via the route's {@code error-profile} (null
     * field OMITTED).
     */
    @Path("/profiled-consumes")
    @JsonProfile(OPINIONATED_PROFILE)
    public static class ProfiledConsumesResource {

        /**
         * Never invoked under the 415 test (the {@code @Consumes} check rejects the mismatched type first).
         *
         * @param body the (never-read) request body
         * @return never returns normally under the 415 test
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "profiledConsumes")
        public Response post(String body) {
            return Response.ok(ErrorEntity.boom()).build();
        }
    }

    /**
     * Static EXPLICIT {@code @JsonProfile("system")} route at {@code /overlap/fixed} with a
     * {@code @Consumes("application/json")}, used by the overlapping-route idempotency regression test.
     * It overlaps with {@link OverlapParamResource} at {@code /overlap/{id}}: a POST to
     * {@code /overlap/fixed} pattern-matches BOTH routes. Registered MOST-SPECIFIC-FIRST (the static
     * segment outranks the {@code {id}} param), so this route's per-route failure handler runs first on
     * the {@code @Consumes} 415: it stashes <em>nothing</em> (its profile is the installed process
     * codec's) and marks the error-body decision as taken. The overlapping param route's failure handler
     * must then short-circuit (the idempotency guard), so the 415 error body is the process codec's own
     * rendering (null field PRESENT), NOT the param route's opinionated profile's.
     */
    @Path("/overlap/fixed")
    @JsonProfile(SYSTEM_PROFILE)
    public static class OverlapFixedResource {

        /**
         * Never invoked under the overlap test (the {@code @Consumes} check rejects the mismatched type first).
         *
         * @param body the (never-read) request body
         * @return never returns normally under the overlap test
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "overlapFixed")
        public Response post(String body) {
            return Response.ok(ErrorEntity.boom()).build();
        }
    }

    /**
     * Profiled {@code @JsonProfile("error-profile")} parameter route at {@code /overlap/{id}}, used by
     * the overlapping-route idempotency regression test. It overlaps with {@link OverlapFixedResource}:
     * a POST to {@code /overlap/fixed} also matches this route ({@code id="fixed"}). Because the static
     * route registers first, this route's per-route failure handler runs SECOND in the failure dispatch.
     * Without the first-decision-wins idempotency guard it would observe
     * {@code KEY_RESOLVED_BODY_MAPPER == null} (the process-codec route stashes nothing) and stash ITS
     * opinionated profile mapper, leaking the wrong route's profile onto the static route's 415 error
     * body. The guard makes it pass through untouched.
     */
    @Path("/overlap/{id}")
    @JsonProfile(OPINIONATED_PROFILE)
    public static class OverlapParamResource {

        /**
         * Returns a trivial 200; never reached on the overlap 415 path (the static route fails first).
         *
         * @param id the path parameter
         * @param body the (never-read) request body
         * @return a 200 response
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "overlapParam")
        public Response post(@jakarta.ws.rs.PathParam("id") String id, String body) {
            return Response.ok(ErrorEntity.boom()).build();
        }
    }

    /**
     * A trivial mounted resource so the no-method test has a valid router to build; the pre-routing
     * middleware fails the request before this (or any) method matches, so this method is never invoked.
     */
    @Path("/unused")
    public static class UnusedResource {

        /**
         * Returns a trivial 200; never reached on the no-method path.
         *
         * @return a 200 {@code text/plain} response
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unused")
        public String get() {
            return "unused";
        }
    }

    /**
     * API-scoped middleware on {@code /*} that fails <em>every</em> request with a 404
     * {@link NoMethodFailure} before any operation route matches — so the no-method failure path runs
     * with no per-method profile mapper stashed. Mirrors a pre-routing rejection (e.g. an undefined-path
     * 404) that reaches the failure handler before method match.
     */
    static final class RejectBeforeMatchMiddleware implements Middleware {
        @Override
        public void handle(RoutingContext ctx) {
            ctx.fail(404, new NoMethodFailure());
        }

        @Override
        public MiddlewareScope scope() {
            return MiddlewareScope.API;
        }

        @Override
        public int priority() {
            return 0;
        }
    }

    // --- Test 1: error in a profiled method uses the profile (PASSES via slice 3.1) ---

    @Test
    @DisplayName(
            "An error in a @JsonProfile method serializes its ProblemDetail body via the profile (omits null), status+media-type preserved")
    void errorInProfiledMethod_usesProfile(Vertx vertx, VertxTestContext ctx) {
        // The class-level @JsonProfile resolves the opinionated mapper, stashed under
        // KEY_RESOLVED_BODY_MAPPER ahead of dispatch. The method throws MappedFailure -> mapped to a 422
        // ErrorEntity body (application/problem+json). The error body reads the stash and OMITS the null
        // "missing" field. Status (422) and media type must be unchanged. PASSES today (slice 3.1).
        deploy(vertx, ctx, Set.of(new ProfiledErrorResource()), Set.of(), (port, c) -> c.get(
                        port, "127.0.0.1", "/profiled-error")
                .send()
                .map(ProfiledErrorResponseIT::bodyWithMeta)
                .onComplete(ctx.succeeding(r -> {
                    ctx.verify(() -> {
                        assertEquals(422, r.status, "the mapped error status must be preserved");
                        assertTrue(
                                r.contentType != null && r.contentType.contains("application/problem+json"),
                                "the problem+json media type must be preserved; was: " + r.contentType);
                        assertTrue(r.body.contains("\"name\":\"boom\""), "name must be present; body=" + r.body);
                        assertFalse(
                                r.body.contains("\"missing\""),
                                "the profile must omit the null field on the error body; body=" + r.body);
                    });
                    ctx.completeNow();
                })));
    }

    // --- Test 2: no-method error uses the boundary+global default (FAILS today) ---

    @Test
    @DisplayName(
            "A no-method error serializes its body via the boundary jaxrs.jsonProfile default (omits null), status+media-type preserved")
    void noMethodError_usesBoundaryDefault(Vertx vertx, VertxTestContext ctx) {
        // jaxrs.jsonProfile=error-profile configures the boundary default. The middleware fails the
        // request with a 404 NoMethodFailure BEFORE any route matches, so NO per-method mapper is stashed.
        // The failure handler must resolve the boundary default and stash it before serializing the 404
        // ErrorEntity body so the null "missing" field is OMITTED. Without that resolution no mapper is
        // stashed on the no-method path, Json.encode runs, and "missing":null is present.
        deploy(
                vertx,
                ctx,
                Set.of(new UnusedResource()),
                Set.of(new RejectBeforeMatchMiddleware()),
                boundaryDefaultConfig(),
                Set.of(opinionatedErrorProfile()),
                (port, c) -> c.get(port, "127.0.0.1", "/anything")
                        .send()
                        .map(ProfiledErrorResponseIT::bodyWithMeta)
                        .onComplete(ctx.succeeding(r -> {
                            ctx.verify(() -> {
                                assertEquals(404, r.status, "the no-method error status must be preserved");
                                assertTrue(
                                        r.contentType != null && r.contentType.contains("application/problem+json"),
                                        "the problem+json media type must be preserved; was: " + r.contentType);
                                assertTrue(
                                        r.body.contains("\"name\":\"boom\""), "name must be present; body=" + r.body);
                                assertFalse(
                                        r.body.contains("\"missing\""),
                                        "the boundary default profile must omit the null field on the no-method error body; body="
                                                + r.body);
                            });
                            ctx.completeNow();
                        })));
    }

    // --- Test 3: fail-open on a profile-mapper throw (FAILS today) ---

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName(
            "A profile-mapper throw on an error body fails open to the process codec, preserving the mapped status + problem+json (no bare 500)")
    void failOpen_onProfileMapperThrow(Vertx vertx, VertxTestContext ctx) {
        // The throwing profile's mapper throws when serializing the ErrorEntity error body. The framework
        // must fall back to the process codec (Json.encode), PRESERVING the mapped 422 status and the
        // application/problem+json media type, and must NOT collapse to a bare 500. Without the fail-open
        // the throw propagates out of the detached serialization callback, the response is never
        // completed, and the client hangs until this test's 5s timeout. The fail-open body is the process
        // codec's rendering of ErrorEntity ("name":"boom").
        deploy(vertx, ctx, Set.of(new ThrowingErrorResource()), Set.of(), (port, c) -> c.get(
                        port, "127.0.0.1", "/throwing-error")
                .send()
                .map(ProfiledErrorResponseIT::bodyWithMeta)
                .onComplete(ctx.succeeding(r -> {
                    ctx.verify(() -> {
                        assertEquals(
                                422,
                                r.status,
                                "fail-open must preserve the mapped status, not collapse to a bare 500; status was "
                                        + r.status);
                        assertTrue(
                                r.contentType != null && r.contentType.contains("application/problem+json"),
                                "fail-open must preserve the problem+json media type; was: " + r.contentType);
                        assertTrue(
                                r.body.contains("\"name\":\"boom\""),
                                "the fail-open body must carry the error entity; body=" + r.body);
                    });
                    ctx.completeNow();
                })));
    }

    // --- Test 4: an unannotated error path serializes through the vertique floor ---

    @Test
    @DisplayName(
            "An error with no @JsonProfile and no default serializes its body through the vertique floor (null omitted)")
    void unannotatedErrorPath_usesTheVertiqueFloor(Vertx vertx, VertxTestContext ctx) {
        // No @JsonProfile and no configured default => the effective profile is the vertique floor, whose
        // mapper the matched route resolves and stashes => the 422 ErrorEntity body carries the floor's
        // opinions and OMITS the null "missing" field, where the process codec's Json.encode would have
        // written "missing":null.
        deploy(vertx, ctx, Set.of(new PlainErrorResource()), Set.of(), (port, c) -> c.get(
                        port, "127.0.0.1", "/plain-error")
                .send()
                .map(ProfiledErrorResponseIT::bodyWithMeta)
                .onComplete(ctx.succeeding(r -> {
                    ctx.verify(() -> {
                        assertEquals(422, r.status, "the mapped error status must be preserved");
                        assertTrue(r.body.contains("\"name\":\"boom\""), "name must be present; body=" + r.body);
                        assertFalse(
                                r.body.contains("\"missing\""),
                                "the vertique floor must omit the null field on the error body; body=" + r.body);
                    });
                    ctx.completeNow();
                })));
    }

    // --- Test 5: 415 on an EXPLICIT-system route under another boundary default ⇒ process-codec body ---

    @Test
    @DisplayName(
            "A 415 on a @JsonProfile(\"system\") route under a non-system boundary default serializes its body via the process codec (null present), status+media-type preserved")
    void consumes415_explicitSystemRoute_usesTheProcessCodecNotBoundaryDefault(Vertx vertx, VertxTestContext ctx) {
        // The graph's own system mapper is installed as the process codec before the router is built (as
        // JsonSystemProfileInstallStep does at CONFIGURE in a booted application), so the route's explicit
        // @JsonProfile("system") resolves that very instance and the identity sentinel returns null: NO
        // request-side mapper is stashed. The @Consumes 415 check then fires before the resource method,
        // with jaxrs.jsonProfile=error-profile configuring a different boundary default. The matched
        // route's per-route failure handler marks the error-body mapper DECIDED with nothing stashed, so
        // handleFailure must NOT overlay the boundary default: the 415 body is the process codec's own
        // rendering (null "missing" PRESENT). Were the boundary default overlaid, the null would be
        // OMITTED (the asymmetry bug).
        deployWithSystemAsProcessCodec(
                vertx, ctx, Set.of(new VertxConsumesResource()), boundaryDefaultConfig(), (port, c) -> c.post(
                                port, "127.0.0.1", "/vertx-consumes")
                        .putHeader("Content-Type", "text/plain")
                        .sendBuffer(Buffer.buffer("not json"))
                        .map(ProfiledErrorResponseIT::bodyWithMeta)
                        .onComplete(ctx.succeeding(r -> {
                            ctx.verify(() -> {
                                assertEquals(415, r.status, "the 415 status must be preserved");
                                assertTrue(
                                        r.contentType != null && r.contentType.contains("application/problem+json"),
                                        "the problem+json media type must be preserved; was: " + r.contentType);
                                assertTrue(
                                        r.body.contains("\"missing\":null"),
                                        "the process codec renders the null field, the boundary default would omit"
                                                + " it; body=" + r.body);
                                assertEquals(
                                        Json.encode(ErrorEntity.boom()),
                                        r.body,
                                        "an explicit-system route's 415 body must be byte-for-byte the process"
                                                + " codec's rendering, NOT the boundary default; body=" + r.body);
                            });
                            ctx.completeNow();
                        })));
    }

    // --- Test 6: 415 on a PROFILED route (415 fires before request-side stash) ⇒ profile error body ---

    @Test
    @DisplayName(
            "A 415 on a @JsonProfile(\"error-profile\") route serializes its body via the profile (omits null) even though the 415 fires before the request-side stash")
    void consumes415_profiledRoute_usesProfile(Vertx vertx, VertxTestContext ctx) {
        // The @Consumes 415 check fires BEFORE the request-side mapper stash (installed after it by
        // JaxRsRouteRegistrar), so without the fix no profile mapper is on the context and the 415 body
        // would serialize via the process codec (null present) or the boundary default. With the fix, the matched
        // route's per-route failure handler stashes the route's error-profile mapper, so the 415 body
        // OMITS the null "missing" field. No boundary default is configured here, isolating the
        // before-stash gap.
        deploy(vertx, ctx, Set.of(new ProfiledConsumesResource()), Set.of(), (port, c) -> c.post(
                        port, "127.0.0.1", "/profiled-consumes")
                .putHeader("Content-Type", "text/plain")
                .sendBuffer(Buffer.buffer("not json"))
                .map(ProfiledErrorResponseIT::bodyWithMeta)
                .onComplete(ctx.succeeding(r -> {
                    ctx.verify(() -> {
                        assertEquals(415, r.status, "the 415 status must be preserved");
                        assertTrue(
                                r.contentType != null && r.contentType.contains("application/problem+json"),
                                "the problem+json media type must be preserved; was: " + r.contentType);
                        assertTrue(r.body.contains("\"name\":\"boom\""), "name must be present; body=" + r.body);
                        assertFalse(
                                r.body.contains("\"missing\""),
                                "the route's profile must omit the null field on the 415 error body even though the "
                                        + "415 fired before the request-side stash; body=" + r.body);
                    });
                    ctx.completeNow();
                })));
    }

    // --- Test 7: overlapping explicit-system static route + profiled param route ⇒ process-codec body ---

    @Test
    @DisplayName(
            "A 415 on an explicit-system static route overlapping a profiled param route serializes its body via the process codec (null present), NOT the param route's profile")
    void consumes415_overlappingExplicitSystemStaticAndProfiledParam_usesTheProcessCodecNotParamProfile(
            Vertx vertx, VertxTestContext ctx) {
        // /overlap/fixed is an EXPLICIT @JsonProfile("system") static route; /overlap/{id} is a profiled
        // (@JsonProfile("error-profile")) param route that ALSO matches /overlap/fixed. Both are POST.
        // The graph's system mapper is installed as the process codec, so the static route's identity
        // sentinel fires and it stashes nothing. Under a boundary default naming another profile
        // (jaxrs.jsonProfile=error-profile), a POST to /overlap/fixed with a mismatched Content-Type
        // fires the static route's @Consumes 415 check. The static route registers MOST-SPECIFIC-FIRST,
        // so its per-route failure handler runs first: it stashes nothing and marks the decision DECIDED.
        // Vert.x then dispatches the failure through the overlapping param route's per-route failure
        // handler too (the ctx.next() chaining proven by FailureHandlerChainProbeIT). With the
        // FIRST-DECISION-WINS idempotency guard, the param route's handler short-circuits, so the 415
        // error body is byte-for-byte the process codec's rendering (null "missing" PRESENT). WITHOUT the
        // guard, the param route's handler observes KEY_RESOLVED_BODY_MAPPER == null and stashes its
        // opinionated profile mapper, so the null field is OMITTED — the leak this test pins.
        deployWithSystemAsProcessCodec(
                vertx,
                ctx,
                Set.of(new OverlapFixedResource(), new OverlapParamResource()),
                boundaryDefaultConfig(),
                (port, c) -> c.post(port, "127.0.0.1", "/overlap/fixed")
                        .putHeader("Content-Type", "text/plain")
                        .sendBuffer(Buffer.buffer("not json"))
                        .map(ProfiledErrorResponseIT::bodyWithMeta)
                        .onComplete(ctx.succeeding(r -> {
                            ctx.verify(() -> {
                                assertEquals(415, r.status, "the 415 status must be preserved");
                                assertTrue(
                                        r.contentType != null && r.contentType.contains("application/problem+json"),
                                        "the problem+json media type must be preserved; was: " + r.contentType);
                                assertTrue(
                                        r.body.contains("\"missing\":null"),
                                        "the process codec renders the null field, the param route's profile would"
                                                + " omit it; body=" + r.body);
                                assertEquals(
                                        Json.encode(ErrorEntity.boom()),
                                        r.body,
                                        "the explicit-system static route's 415 body must be byte-for-byte the"
                                                + " process codec's rendering; an overlapping profiled param route"
                                                + " must NOT leak its profile onto it; body=" + r.body);
                            });
                            ctx.completeNow();
                        })));
    }

    // --- Helpers ---

    /** Carrier for a response's status code, Content-Type, and body string. */
    private record ResponseMeta(int status, String contentType, String body) {}

    /**
     * Pairs the already-aggregated body of {@code response} with its status and Content-Type.
     *
     * <p>The body is read through {@code bodyAsString()} and wrapped in {@code String.valueOf}: a
     * {@link WebClient} reports an empty body as {@code null} where the raw client reported a
     * zero-length buffer. No error response asserted on here is legitimately empty — every one carries
     * a mapped {@code ErrorEntity} — so the wrapper only keeps an unexpected empty body a legible
     * assertion failure instead of an NPE inside {@code ctx.verify}.
     *
     * @param response the aggregated client response
     * @return the status + Content-Type + body string
     */
    private static ResponseMeta bodyWithMeta(HttpResponse<Buffer> response) {
        return new ResponseMeta(
                response.statusCode(), response.getHeader("Content-Type"), String.valueOf(response.bodyAsString()));
    }

    /**
     * Returns the boundary configuration selecting {@code jaxrs.jsonProfile=error-profile} — the
     * boundary default the boundary-default and overlapping-route regression tests deploy under, chosen
     * so it differs from both the process codec's profile and the {@code vertique} floor.
     *
     * @return the boundary-default configuration
     */
    private static JsonObject boundaryDefaultConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("jsonProfile", OPINIONATED_PROFILE));
    }

    /**
     * Deploys {@code resources} and {@code middlewares} under the {@code web-validation} strategy with no
     * configured default profile and the opinionated {@code error-profile} registered, then invokes
     * {@code afterListen}.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resources the JAX-RS resources to mount
     * @param middlewares the middlewares to mount
     * @param afterListen callback invoked with the bound port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<Middleware> middlewares,
            BiConsumer<Integer, WebClient> afterListen) {
        deploy(
                vertx,
                ctx,
                resources,
                middlewares,
                new JsonObject(),
                Set.of(opinionatedErrorProfile(), throwingErrorProfile()),
                afterListen);
    }

    /**
     * Deploys {@code resources} and {@code middlewares} under the {@code web-validation} strategy with the
     * given configuration and profile set, leaving the process JSON codec untouched.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resources the JAX-RS resources to mount
     * @param middlewares the middlewares to mount
     * @param config the application configuration (carries the optional {@code jaxrs.jsonProfile} default)
     * @param profiles the profiles to register
     * @param afterListen callback invoked with the bound port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<Middleware> middlewares,
            JsonObject config,
            Set<JsonMapperProfile> profiles,
            BiConsumer<Integer, WebClient> afterListen) {
        deploy(vertx, ctx, resources, middlewares, config, profiles, false, afterListen);
    }

    /**
     * Deploys {@code resources} with the graph's own {@code system} mapper installed as the process JSON
     * codec before the router is built, and the opinionated {@code error-profile} registered.
     *
     * <p>This is what a booted application's {@code CONFIGURE} phase does, and it is what makes an
     * explicit {@code @JsonProfile("system")} route take the "no override" fast path: the resolver's
     * sentinel compares the resolved mapper against {@code VertiqueJson.mapper()} by identity, so the
     * mapper must come from the graph's own registry — every registry instance owns its own instances.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resources the JAX-RS resources to mount
     * @param config the application configuration (carries the {@code jaxrs.jsonProfile} default)
     * @param afterListen callback invoked with the bound port and the shared HTTP client
     */
    private void deployWithSystemAsProcessCodec(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            JsonObject config,
            BiConsumer<Integer, WebClient> afterListen) {
        deploy(vertx, ctx, resources, Set.of(), config, Set.of(opinionatedErrorProfile()), true, afterListen);
    }

    /**
     * Deploys {@code resources} and {@code middlewares} under the {@code web-validation} strategy with the
     * given configuration and profile registry, optionally installing the graph's {@code system} mapper as
     * the process JSON codec, starts an HTTP server, and invokes {@code afterListen} with the bound port
     * and a shared {@link WebClient}. The client is bound to a field so {@link #tearDown} can close it; an
     * unbound client can never be closed at all.
     *
     * <p>Built through {@link MountFixtures} over {@link ValidationMountComponent}, so the graph carries
     * every real production collaborator (including the production {@code JsonBodyEncoder} — the
     * {@code ErrorEntity} bodies this test asserts on never match any of the other five production
     * encoders) plus this test's contributed middlewares, profiles, and exception mappers.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resources the JAX-RS resources to mount
     * @param middlewares the middlewares to mount
     * @param config the application configuration (carries the optional {@code jaxrs.jsonProfile} default)
     * @param profiles the profiles to register
     * @param installSystemAsProcessCodec whether to install the graph's {@code system} mapper as the
     *     process JSON codec before the router is built ({@link #resetProcessCodec} undoes it)
     * @param afterListen callback invoked with the bound port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<Middleware> middlewares,
            JsonObject config,
            Set<JsonMapperProfile> profiles,
            boolean installSystemAsProcessCodec,
            BiConsumer<Integer, WebClient> afterListen) {
        RestTestContributions.Builder contributions = RestTestContributions.builder()
                // Maps @Consumes 415 (raised as NotSupportedException by JaxRsRouteRegistrar's
                // per-route 415 check) to an observable ErrorEntity body so the process-codec-vs-profile
                // null-omission difference is visible (ProblemDetail would be NON_NULL and thus
                // indistinguishable).
                .addExceptionMapper(new MappedFailureMapper())
                .addExceptionMapper(new NoMethodFailureMapper())
                .addExceptionMapper(new UnsupportedMediaTypeMapper());
        middlewares.forEach(contributions::addMiddleware);
        profiles.forEach(contributions::addJsonMapperProfile);

        ValidationMountComponent component = MountFixtures.component(vertx, config, contributions.build());
        if (installSystemAsProcessCodec) {
            // Before the router is built: the resolver captures its sentinel comparison at router-build
            // time, so an install afterwards would no longer match the routes already decided.
            VertiqueJson.install(
                    JsonProfileId.SYSTEM, component.jsonMapperProfileRegistry().mapper(JsonProfileId.SYSTEM));
        }
        RestTestMounts.startServer(vertx, component.testMount(), resources).onComplete(ctx.succeeding(s -> {
            server = s;
            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            afterListen.accept(s.actualPort(), client);
        }));
    }

    /** Maps {@link MappedFailure} to a 422 {@link ErrorEntity} {@code application/problem+json} body. */
    private static final class MappedFailureMapper implements ExceptionMapper<MappedFailure> {
        @Override
        public Response toResponse(MappedFailure exception) {
            return Response.status(422)
                    .entity(ErrorEntity.boom())
                    .type("application/problem+json")
                    .build();
        }
    }

    /** Maps {@link NoMethodFailure} to a 404 {@link ErrorEntity} {@code application/problem+json} body. */
    private static final class NoMethodFailureMapper implements ExceptionMapper<NoMethodFailure> {
        @Override
        public Response toResponse(NoMethodFailure exception) {
            return Response.status(404)
                    .entity(ErrorEntity.boom())
                    .type("application/problem+json")
                    .build();
        }
    }

    /** Maps {@link NotSupportedException} (the {@code @Consumes} 415) to an observable {@link ErrorEntity} body. */
    private static final class UnsupportedMediaTypeMapper implements ExceptionMapper<NotSupportedException> {
        @Override
        public Response toResponse(NotSupportedException exception) {
            return Response.status(415)
                    .entity(ErrorEntity.boom())
                    .type("application/problem+json")
                    .build();
        }
    }
}
