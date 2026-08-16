// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * RED integration test for Phase 3 Slice 3.1 (FR-JSON-055/056/057): JAX-RS JSON <em>response</em>
 * serialization must become symmetric with request binding — a resource method's response should
 * serialize through the same effective profile resolved for its request (the per-method mapper the
 * request path already stashes under {@code BoundRequest.KEY_RESOLVED_BODY_MAPPER}), while a method
 * with no profile and no configured default stays byte-for-byte on the {@code vertx} mapper
 * ({@code Json.encode}).
 *
 * <p>The encoder under test is the <strong>production</strong> {@code JsonBodyEncoder}, reached through
 * {@link MountFixtures} over {@link ValidationMountComponent} (the {@code vertique-rest-test} fixture),
 * so the RED→green transition (now green) is genuinely driven by the production encoder's behavior —
 * not a re-implemented stub. Before slice 3.1, {@code JsonBodyEncoder.encode} called
 * {@code Json.encode(entity)} unconditionally and ignored the stash, so the <em>profiled</em> test below
 * FAILED (the opinionated profile's observable behavior never reached the wire). Slice 3.1 made the
 * encoder read the stash, turning it green.
 *
 * <p>The opinionated {@code response-profile} test profile's observable difference from
 * {@code Json.encode} is that it omits {@code null} fields (a {@code NON_NULL} mix-in scoped to the
 * response entity type). This difference survives the registry's structural round-trip probe because
 * the {@code NON_NULL} inclusion is scoped to the entity class via a mix-in — the probe serializes a
 * null-bearing {@code JsonObject}, which is unaffected. Null-omission is chosen as the observable
 * because {@code Json.encode} renders it <em>successfully</em> (a 200 response carrying
 * {@code "missing":null}), so the RED signal is a clean body-content assertion rather than a coarse
 * encode failure: a {@code java.time} value would make the bare {@code vertx} mapper throw (it lacks
 * jsr310) and the response would never reach the wire, turning the RED into a hang.
 *
 * <p><strong>Why a {@link WebClient} and not a raw {@code HttpClient}.</strong> A raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load {@code body()} can succeed with zero bytes while the status code is correct (issue #167).
 * Both assertions here are about the <em>bytes of the response body</em> — whether the null field is
 * omitted, and a byte-for-byte comparison against {@code Json.encode} — so a silently emptied body
 * would report a profile-selection defect that did not happen. A {@link WebClient} aggregates the body
 * into its {@code HttpResponse} before completing the send, so the race is closed by construction
 * rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledResponseSerializationIT {

    private static final String OPINIONATED_PROFILE = "response-profile";

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
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Opinionated profile fixture ---

    /**
     * Builds the {@code response-profile} profile: a Jackson mapper with Vert.x JSON support (required
     * by the registry probe and so {@code JsonObject} round-trips), the jsr310 module with
     * {@code WRITE_DATES_AS_TIMESTAMPS} disabled (the opinionated date policy, retained for parity with
     * the framework's {@code vertique} profile), and a {@code NON_NULL} mix-in scoped to
     * {@link ProfiledEntity} (so the entity's null field is omitted while the structural probe's
     * null-bearing {@code JsonObject} is unaffected). The null-omission is the observable difference
     * from the {@code vertx} mapper's {@code Json.encode}.
     *
     * @return the {@code response-profile} profile
     */
    private static JsonMapperProfile opinionatedResponseProfile() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(VertxJsonSupport.module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mapper.addMixIn(ProfiledEntity.class, NonNullMixin.class);
        return JsonMapperProfiles.of(JsonProfileId.of(OPINIONATED_PROFILE), mapper);
    }

    /** Mix-in applying {@code NON_NULL} inclusion to the response entity only (not the probe samples). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class NonNullMixin {}

    // --- Entity fixtures ---

    /**
     * Response entity carrying a present {@code name} and a {@code null} {@code missing} field. Under the
     * opinionated profile (via the {@code NON_NULL} mix-in) the null field is omitted; under
     * {@code Json.encode} the null is included.
     */
    public static class ProfiledEntity {
        public String name;
        public String missing;
    }

    /**
     * Response entity with a present {@code name} and a {@code null} {@code missing} field. Used by the
     * vertx-unchanged invariant test, whose body must equal {@code Json.encode(entity)}.
     */
    public static class PlainEntity {
        public String name;
        public String missing;
    }

    // --- Resource fixtures ---

    /**
     * Resource selecting the opinionated {@code response-profile} at the class level. Its GET returns a
     * {@link ProfiledEntity} (null field) as an {@code application/json} response; the effective response
     * serialization must run through the profile mapper, which omits the null field.
     */
    @Path("/profiled")
    @JsonProfile(OPINIONATED_PROFILE)
    public static class ProfiledResource {

        /**
         * Returns a {@link ProfiledEntity} with a null field.
         *
         * @return a 200 {@code application/json} response wrapping the entity
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "profiledResponse")
        public Response get() {
            ProfiledEntity entity = new ProfiledEntity();
            entity.name = "alice";
            entity.missing = null;
            return Response.ok(entity).type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * Resource with NO {@code @JsonProfile} (and no configured default), so the effective response
     * profile is {@code vertx}. Its GET returns a {@link PlainEntity} (null field, no date); the
     * response body must be byte-for-byte identical to today's {@code Json.encode(entity)}.
     */
    @Path("/plain")
    public static class PlainResource {

        /**
         * Returns a {@link PlainEntity} with a null field.
         *
         * @return a 200 {@code application/json} response wrapping the entity
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "plainResponse")
        public Response get() {
            PlainEntity entity = new PlainEntity();
            entity.name = "alice";
            entity.missing = null;
            return Response.ok(entity).type(MediaType.APPLICATION_JSON).build();
        }
    }

    // --- Test: a profiled method serializes its response via the profile ---

    @Test
    @DisplayName("A @JsonProfile method serializes its response via the profile (omits the null field)")
    void profiledMethod_serializesViaProfile(Vertx vertx, VertxTestContext ctx) {
        // The class-level @JsonProfile resolves the opinionated mapper, which the request path stashes
        // under KEY_RESOLVED_BODY_MAPPER ahead of dispatch. Once JsonBodyEncoder reads that stash, the
        // response body OMITS the null "missing" field. TODAY the encoder uses Json.encode (vertx) and
        // ignores the stash, so the body still carries "missing":null and this assertion FAILS (the RED
        // signal): the profile's omit-nulls behavior never reaches the wire.
        get(vertx, ctx, new ProfiledResource(), "/profiled", body -> {
            assertTrue(body.contains("\"name\":\"alice\""), "the name field must be present; body was: " + body);
            assertFalse(
                    body.contains("\"missing\""),
                    "the opinionated profile must omit the null field; body was: " + body);
        });
    }

    // --- Test: a vertx (default) method's response is byte-for-byte unchanged ---

    @Test
    @DisplayName("A method with no @JsonProfile serializes its response byte-for-byte via Json.encode (vertx)")
    void vertxMethod_byteForByteUnchanged(Vertx vertx, VertxTestContext ctx) {
        // No @JsonProfile and no configured default => the effective profile is vertx => no mapper is
        // stashed => JsonBodyEncoder must keep calling Json.encode(entity) exactly. The body must equal
        // the test-computed Json.encode of the same entity (null field PRESENT), proving the vertx path
        // is byte-for-byte unchanged (FR-JSON-057). This PASSES today and guards the invariant.
        PlainEntity expected = new PlainEntity();
        expected.name = "alice";
        expected.missing = null;
        String expectedBody = Json.encode(expected);

        get(
                vertx,
                ctx,
                new PlainResource(),
                "/plain",
                body -> assertEquals(
                        expectedBody, body, "the vertx (default) response must be byte-for-byte Json.encode output"));
    }

    // --- Helpers ---

    /**
     * Deploys {@code resource}, issues a {@code GET} to {@code path}, and runs {@code assertion} on the
     * response body string.
     *
     * <p>Built through {@link MountFixtures} over {@link ValidationMountComponent} (the {@code
     * vertique-rest-test} fixture), which is wired with the real {@code web-validation} strategy, the
     * victools {@code AnnotationSchemaSource}, and — crucially — the <strong>production</strong>
     * {@code JsonBodyEncoder} as the JSON response encoder, so the response path exercises the real
     * encoder under test. The opinionated {@code response-profile} is contributed via
     * {@link RestTestContributions}, joining the framework's own profile set.
     *
     * <p>The {@link WebClient} is bound to a field so {@link #tearDown} can close it; an unbound client
     * can never be closed at all. Its body is read through {@code bodyAsString()} and wrapped in
     * {@code String.valueOf}: a {@link WebClient} reports an empty body as {@code null} where the raw
     * client reported a zero-length buffer, and neither response asserted on here is legitimately
     * empty, so the wrapper only keeps an unexpected empty body a legible assertion failure instead of
     * an NPE inside {@code ctx.verify}.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resource the JAX-RS resource to mount
     * @param path the request path
     * @param assertion the assertion on the response body
     */
    private void get(
            Vertx vertx,
            VertxTestContext ctx,
            Object resource,
            String path,
            java.util.function.Consumer<String> assertion) {
        RestTestContributions contributions = RestTestContributions.builder()
                .addJsonMapperProfile(opinionatedResponseProfile())
                .build();
        RestTestMounts.startServer(vertx, MountFixtures.mount(vertx, contributions), Set.of(resource))
                .compose(s -> {
                    server = s;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return client.get(s.actualPort(), "127.0.0.1", path)
                            .send()
                            .map(response -> String.valueOf(response.bodyAsString()));
                })
                .onComplete(ctx.succeeding(body -> {
                    ctx.verify(() -> assertion.accept(body));
                    ctx.completeNow();
                }));
    }
}
