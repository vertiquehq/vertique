// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.constraints.URL;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-005 (rest-022 T021, FR-020, AC-020.3). Over a real HTTP round trip through the {@code
 * web-validation} gate, on a {@link MountFixtures#validatorBackedMount validator-backed mount}: a body
 * whose DTO carries Hibernate Validator's {@code @URL} (rendered {@code format: uri} plus the composed
 * {@code @Pattern}'s {@code allOf} entries by T004's {@code MetadataConstraintSource} supplement)
 * accepts a 3,000-character well-formed value and rejects a malformed one as a format violation,
 * never a stack overflow, and rejects a 4,097-character well-formed value as T004's per-string {@code
 * patternInputLength} bound: {@code @URL} composes {@code @Pattern}, so the field is bounded and
 * counted by design and AC-020.3's bounded-amendment clause governs it (coordinator ruling E21); a
 * body whose DTO
 * carries {@code @Schema(format = "url")} judges a slow-for-the-engine value ({@link #SLOW_URL}) and a
 * link-local address within a bounded request timeout, preserving the engine's private-range filtering
 * (C-FORMAT's {@code url} rule).
 *
 * <p><strong>Expected initial result (behavior-change, red at T004's head, {@code 76e42141}).</strong>
 * The unmodified engine's {@code uri} expression overflows the stack near 3,000 characters (S-001), so
 * the 3,000-character {@code link} row is expected red there (this class records the observed response
 * rather than asserting a specific failure shape, since an overflow's exact HTTP symptom is not itself
 * part of the contract). The 4,097-character {@code link} row's corrected oracle (coordinator ruling
 * E21: T004's per-string {@code patternInputLength} bound, since {@code @URL} composes {@code
 * @Pattern}) already holds at this head in isolation, but this class's overall expected initial result
 * stays red here regardless, because {@link #SLOW_URL} aborts the method with a timeout before the
 * final {@code assertAll} runs. {@link #SLOW_URL} is constructed so the unmodified {@code
 * url} expression takes far longer than the request timeout to decide it (about 82 s measured on a 1
 * MB-stack thread, L02), so that row is expected red by timeout there — its own Maven invocation, since
 * the server's event loop keeps computing after the client gives up (T004's {@code
 * PatternInputBoundIT} cleanup note). The short invalid {@code link} value and the link-local {@code
 * site} address are already rejected at T004's head (the engine still decides {@code uri}/{@code url}
 * for short values), so those rows, and the ones they act as oracles for, characterize rather than
 * change at that baseline.
 *
 * <p>The {@code post}/{@code start} helpers and the {@link Reply} record are copied from T004's {@code
 * PatternInputBoundIT} ({@code start} at line 558, {@code post} at line 574 there, both private) rather
 * than shared, per the coordinator's ruling E5: T004's tests are not edited, and the new tests never
 * reference T004's private members.
 *
 * <p>Requests are issued through a {@link WebClient}, which aggregates the response body before its
 * future completes (issue #167).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FormatCheckReuseIT {

    /**
     * {@code LONG_URI} (the contract's recurring value): 3,000 characters, a valid absolute URI and URL,
     * near which the engine's {@code uri} expression overflows a 1 MB stack (S-001).
     */
    private static final String LONG_URI = "https://example.com/" + "a".repeat(2980);

    /**
     * {@code SLOW_URL} (L02): a 3,000-character single-label host the unmodified {@code url} expression
     * takes about 82 s to judge on a 1 MB-stack thread (measured 81,812 ms), far longer than {@link
     * #REQUEST_TIMEOUT_SECONDS}. C-FORMAT's recorded verdict is invalid (no dot in the host), equal to
     * the engine's.
     */
    private static final String SLOW_URL = "http://" + "a".repeat(2993);

    /**
     * E20's short invalid oracle for {@code url}, reused here as the calibration value for {@code
     * site}'s format-violation detail: rejected by both the engine and C-FORMAT.
     */
    private static final String SHORT_INVALID_SITE = "http://a b";

    /** A run of characters no response may contain: a fragment of every long value this class posts. */
    private static final String A_RUN = "a".repeat(16);

    /** How long each request is awaited: bounds {@link #SLOW_URL}'s red run without hanging the client. */
    private static final long REQUEST_TIMEOUT_SECONDS = 10;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);

    private static final long CLOSE_SECONDS = 15;

    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Bootstrapped with {@link ParameterMessageInterpolator}, matching {@code RestTestValidatorModule}'s
     * construction (the design's no-EL finding): built directly here, rather than through the mount's own
     * Dagger graph, so the Given check below can generate a schema exactly as the validator-backed mount
     * does without exposing a {@link Validator} singleton from the component.
     */
    private static final Validator VALIDATOR = Validation.byProvider(HibernateValidator.class)
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    // --- Fixture state ---

    private Vertx vertx;

    private WebClient client;

    private final List<HttpServer> servers = new ArrayList<>();

    /**
     * Captures the per-test Vert.x instance and creates the {@link WebClient}.
     *
     * @param injectedVertx the per-test Vert.x instance injected by vertx-junit5
     */
    @BeforeEach
    void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the {@link WebClient} and then every server the test started. A {@link #SLOW_URL} red run
     * leaves the server's event loop busy well past this method's own bounded wait; the close simply
     * times out rather than hanging the JVM indefinitely.
     *
     * @throws Exception when a server close fails or times out
     */
    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
        for (HttpServer server : servers) {
            server.close().toCompletionStage().toCompletableFuture().get(CLOSE_SECONDS, TimeUnit.SECONDS);
        }
        servers.clear();
    }

    // --- TP-005 ---

    /**
     * TP-005 (AC-020.3). See the class Javadoc for the full Given/When/Then and the expected initial
     * (red) result.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("TP-005: over HTTP, @URL and url values are judged without overflow or runaway time")
    void uriAndUrlFormatsOverHttp() throws Exception {
        assertAll(
                "the Given: the generated body schemas carry format: uri at link (with @URL's composed"
                        + " @Pattern allOf entries, ruling E21) and format: url at site",
                () -> assertEquals("uri", schemaFormatOf(LinkBody.class, "link"), "LinkBody's link member"),
                () -> assertEquals(
                        new JsonArray()
                                .add(new JsonObject().put("pattern", ""))
                                .add(new JsonObject().put("pattern", ".*")),
                        schemaMemberOf(LinkBody.class, "link").getJsonArray("allOf"),
                        "LinkBody's link member must carry @URL's composed @Pattern allOf entries"
                                + " (ruling E21): they are why T004's per-string pattern-input bound"
                                + " applies to link despite format: uri being unbounded on its own"),
                () -> assertEquals("url", schemaFormatOf(SiteBody.class, "site"), "SiteBody's site member"));

        LinkResource linkResource = new LinkResource();
        SiteResource siteResource = new SiteResource();
        int port = start(
                MountFixtures.validatorBackedMount(vertx, RestTestContributions.none()),
                Set.of(linkResource, siteResource));

        Reply longUri = post(port, "/uri-format/link", linkBody(LONG_URI), REQUEST_TIMEOUT_SECONDS);
        int afterLongUri = linkResource.invocations.get();

        Reply spacedLongUri =
                post(port, "/uri-format/link", linkBody(withSpaceAt(LONG_URI, 1500)), REQUEST_TIMEOUT_SECONDS);

        Reply shortInvalidLink =
                post(port, "/uri-format/link", linkBody("https://example.com/a b"), REQUEST_TIMEOUT_SECONDS);

        Reply overLongLink = post(
                port, "/uri-format/link", linkBody("https://example.com/" + "a".repeat(4077)), REQUEST_TIMEOUT_SECONDS);
        int afterOverLongLink = linkResource.invocations.get();

        Reply shortInvalidSite = post(port, "/url-format/site", siteBody(SHORT_INVALID_SITE), REQUEST_TIMEOUT_SECONDS);

        Reply linkLocalSite = post(
                port,
                "/url-format/site",
                siteBody("http://169.254.169.254/latest/meta-data/"),
                REQUEST_TIMEOUT_SECONDS);

        // SLOW_URL last (E11): its baseline red leaves the server's event loop busy well past this
        // request's own timeout.
        Reply slowUrl = post(port, "/url-format/site", siteBody(SLOW_URL), REQUEST_TIMEOUT_SECONDS);

        assertAll(
                "AC-020.3",
                () -> assertEquals(
                        200,
                        longUri.status(),
                        "LONG_URI (3,000 characters) must be judged without a stack overflow; body: "
                                + abbreviate(longUri.body())),
                () -> assertEquals(1, afterLongUri, "LONG_URI must reach the resource exactly once"),
                () -> assertEquals(
                        400,
                        spacedLongUri.status(),
                        "a 3,000-character value with one malformed character must be rejected as a format"
                                + " violation, not a stack overflow; body: " + abbreviate(spacedLongUri.body())),
                () -> assertSingleDetailMatches(
                        spacedLongUri,
                        shortInvalidLink,
                        "the malformed 3,000-character value's detail must equal the short invalid link"
                                + " value's detail"),
                () -> assertFalse(
                        spacedLongUri.body().contains(A_RUN),
                        "the malformed value must never be echoed; body: " + abbreviate(spacedLongUri.body())),
                () -> assertSingleBoundDetail(
                        overLongLink,
                        "AC-020.3's bounded-amendment clause (coordinator ruling E21): @URL composes"
                                + " @Pattern, so the 4,097-character value is T004's per-string"
                                + " patternInputLength bound like any other patterned string, not"
                                + " AC-020.3's unbounded-uri clause; body: " + abbreviate(overLongLink.body())),
                () -> assertFalse(
                        overLongLink.body().contains(A_RUN),
                        "the over-long value must never be echoed; body: " + abbreviate(overLongLink.body())),
                () -> assertEquals(
                        afterLongUri,
                        afterOverLongLink,
                        "the rejected 4,097-character value must not reach the resource, leaving the count"
                                + " from LONG_URI unchanged"),
                () -> assertEquals(
                        400,
                        slowUrl.status(),
                        "SLOW_URL's recorded C-FORMAT verdict is invalid (a single-label host), and it must be"
                                + " answered within the request timeout; body: " + abbreviate(slowUrl.body())),
                () -> assertEquals(
                        400,
                        linkLocalSite.status(),
                        "a link-local address must be rejected: url keeps the engine's private-range"
                                + " filtering; body: " + abbreviate(linkLocalSite.body())),
                () -> assertSingleDetailMatches(
                        linkLocalSite,
                        shortInvalidSite,
                        "the link-local address's detail must equal the short invalid site value's detail"),
                () -> assertEquals(
                        0, siteResource.invocations.get(), "no rejected site request may reach the resource"));
    }

    // --- Assertions ---

    /**
     * Asserts that {@code actual} and {@code oracle} each carry exactly one detail and that {@code
     * actual}'s detail equals {@code oracle}'s in {@code path}, {@code location}, {@code type}, and
     * {@code detail} — the gate's fixed, value-free format-violation detail (T004's {@code
     * resolveConstraintArgs}), which must not depend on which invalid value produced it.
     *
     * @param actual  the reply under test
     * @param oracle  the reply whose one detail is the comparison oracle
     * @param message the assertion group's message
     */
    private static void assertSingleDetailMatches(Reply actual, Reply oracle, String message) {
        JsonArray actualErrors = actual.errors();
        JsonArray oracleErrors = oracle.errors();
        JsonObject actualDetail =
                actualErrors == null || actualErrors.isEmpty() ? new JsonObject() : actualErrors.getJsonObject(0);
        JsonObject oracleDetail =
                oracleErrors == null || oracleErrors.isEmpty() ? new JsonObject() : oracleErrors.getJsonObject(0);
        assertAll(
                message,
                () -> assertEquals(
                        1,
                        actualErrors == null ? -1 : actualErrors.size(),
                        "actual detail count; body: " + abbreviate(actual.body())),
                () -> assertEquals(
                        1,
                        oracleErrors == null ? -1 : oracleErrors.size(),
                        "oracle detail count; body: " + abbreviate(oracle.body())),
                () -> assertEquals(oracleDetail.getString("path"), actualDetail.getString("path"), "path"),
                () -> assertEquals(oracleDetail.getString("location"), actualDetail.getString("location"), "location"),
                () -> assertEquals(oracleDetail.getString("type"), actualDetail.getString("type"), "type"),
                () -> assertEquals(oracleDetail.getString("detail"), actualDetail.getString("detail"), "detail"));
    }

    /**
     * Asserts a 400 whose {@code errors} array holds exactly one value-free {@code
     * patternInputLength} detail bounding the body at {@code jaxrs.validationPatternMaxChars}'s
     * default (4,096 characters): {@code path} {@code ""}, {@code location} {@code "body"}, {@code
     * type} {@code patternInputLength}, and {@code args} {@code {"maxChars": 4096}} — T004's
     * per-string bound detail (helper copied from {@code PatternInputBoundIT#assertSingleBoundDetail},
     * private there, per E5).
     *
     * @param reply   the reply under test
     * @param message the assertion group's message
     */
    private static void assertSingleBoundDetail(Reply reply, String message) {
        JsonArray errors = reply.errors();
        JsonObject detail = errors == null || errors.isEmpty() ? new JsonObject() : errors.getJsonObject(0);
        assertAll(
                message,
                () -> assertEquals(400, reply.status(), "status; body: " + abbreviate(reply.body())),
                () -> assertEquals(
                        1, errors == null ? -1 : errors.size(), "detail count; body: " + abbreviate(reply.body())),
                () -> assertEquals("", detail.getString("path"), "path"),
                () -> assertEquals("body", detail.getString("location"), "location"),
                () -> assertEquals("patternInputLength", detail.getString("type"), "type"),
                () -> assertEquals(new JsonObject().put("maxChars", 4096), detail.getJsonObject("args"), "args"));
    }

    /**
     * Returns the body schema {@link AnnotationSchemaSource} generates for {@code property} of {@code
     * dto} under the {@code vertique} floor profile, with {@link #VALIDATOR} present — the same
     * generator the validator-backed mount wires through Dagger (T004's {@code
     * MetadataConstraintSource} supplement).
     *
     * @param dto      the body type
     * @param property the top-level property name
     * @return the property's schema, or {@code null} when absent
     */
    private static JsonObject schemaMemberOf(Class<?> dto, String property) {
        JsonObject schema = new AnnotationSchemaSource(Optional.of(VALIDATOR))
                .schemasFor(
                        StubDescriptors.builder()
                                .httpMethod("POST")
                                .body(new BodyDescriptor(dto, null, List.of()))
                                .build(),
                        new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique")))
                .bodySchema()
                .orElseThrow();
        JsonObject properties = schema.getJsonObject("properties");
        return properties == null ? null : properties.getJsonObject(property);
    }

    /**
     * Returns the {@code format} value of {@code property} in {@link #schemaMemberOf}'s schema.
     *
     * @param dto      the body type
     * @param property the top-level property name
     * @return the property's {@code format} value, or {@code null} when absent
     */
    private static String schemaFormatOf(Class<?> dto, String property) {
        JsonObject member = schemaMemberOf(dto, property);
        return member == null ? null : member.getString("format");
    }

    // --- Mounts, requests, and bodies ---

    /**
     * Starts a server for the mount through {@link RestTestMounts} and records it for teardown.
     *
     * @param mount     the mount
     * @param resources the resources
     * @return the bound port
     */
    private int start(RestTestMount mount, Set<Object> resources) {
        HttpServer server = RestTestMounts.startServerBlocking(vertx, mount, resources, START_TIMEOUT);
        servers.add(server);
        return server.actualPort();
    }

    /**
     * POSTs {@code body} as {@code application/json}.
     *
     * @param port    the port
     * @param path    the path
     * @param body    the body
     * @param seconds how long the reply is awaited
     * @return the reply
     * @throws Exception when the round trip fails or times out
     */
    private Reply post(int port, String path, JsonObject body, long seconds) throws Exception {
        return Reply.of(client.post(port, LOOPBACK, path)
                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                .sendBuffer(body.toBuffer())
                .toCompletionStage()
                .toCompletableFuture()
                .get(seconds, TimeUnit.SECONDS));
    }

    private static JsonObject linkBody(String link) {
        return new JsonObject().put("link", link);
    }

    private static JsonObject siteBody(String site) {
        return new JsonObject().put("site", site);
    }

    /**
     * Returns {@code value} with the character at {@code index} replaced by a space, keeping its length.
     *
     * @param value the value
     * @param index the index
     * @return the value with one character replaced
     */
    private static String withSpaceAt(String value, int index) {
        return value.substring(0, index) + " " + value.substring(index + 1);
    }

    private static String abbreviate(String body) {
        return body.length() > 600 ? body.substring(0, 600) + "...(" + body.length() + " chars)" : body;
    }

    /**
     * A reply: status, content type, body, and the problem body's {@code errors}.
     *
     * @param status      the status
     * @param contentType the content type, or {@code ""}
     * @param body        the body, or {@code ""}
     * @param errors      the {@code errors} array, or {@code null} when the body is not a problem body
     */
    private record Reply(int status, String contentType, String body, JsonArray errors) {

        static Reply of(HttpResponse<Buffer> response) {
            String body = response.bodyAsString() == null ? "" : response.bodyAsString();
            String contentType = response.getHeader("Content-Type");
            JsonArray errors;
            try {
                errors = new JsonObject(body).getJsonArray("errors");
            } catch (DecodeException | ClassCastException notAProblemBody) {
                errors = null;
            }
            return new Reply(response.statusCode(), contentType == null ? "" : contentType, body, errors);
        }
    }

    // --- Resources and bodies ---

    /**
     * TP-005: a body carrying Hibernate Validator's {@code @URL}, which T004's {@code
     * MetadataConstraintSource} supplement renders as {@code format: uri}.
     */
    public static class LinkBody {

        /** The URL-constrained member. */
        @URL
        public String link;
    }

    /** Accepts {@link LinkBody}, counting invocations. */
    @Path("/uri-format")
    public static class LinkResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the link's length.
         *
         * @param body the body
         * @return the length
         */
        @POST
        @Path("/link")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "formatCheckReuseLink")
        public String link(LinkBody body) {
            invocations.incrementAndGet();
            return "link=" + body.link.length();
        }
    }

    /** TP-005: a body carrying {@code @Schema(format = "url")}. */
    public static class SiteBody {

        /** The url-formatted member. */
        @Schema(format = "url")
        public String site;
    }

    /** Accepts {@link SiteBody}, counting invocations. */
    @Path("/url-format")
    public static class SiteResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the site's length.
         *
         * @param body the body
         * @return the length
         */
        @POST
        @Path("/site")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "formatCheckReuseSite")
        public String site(SiteBody body) {
            invocations.incrementAndGet();
            return "site=" + body.site.length();
        }
    }
}
