// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
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
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proofs of the {@code web-validation} gate's pattern-input bound (rest-022 T004, FR-020)
 * through a real mount: a string value or object key longer than {@code jaxrs.validationPatternMaxChars}
 * (default 4,096 UTF-16 code units) that reaches a pattern position is rejected before the pattern
 * runs, a request whose strings at such positions exceed {@code jaxrs.validationPatternMaxTotalChars}
 * (default 262,144) in total is rejected at the crossing, and pattern-free positions are neither
 * bounded nor counted. Each rejection is a 400 carrying one value-free detail.
 *
 * <p><strong>Two halves are preservation proofs pinned at the baseline.</strong> TP-009's 4,096-character
 * worst case and TP-016's pattern-free bulk body are answered by the unmodified gate (open-core
 * {@code 2f54d2d9}) exactly as the {@code BASELINE_*} constants record; the bound must leave both answers
 * unchanged.
 *
 * <p><strong>Catastrophic-input methods.</strong> {@link #bodyValueBoundaryOverHttp()} and
 * {@link #overLongPatternedHeaderIsRejected()} send a value to {@link #CATASTROPHIC}, which the gate
 * answers only if it never evaluates the pattern. Without the bound the evaluation blocks a server
 * event loop until the JVM exits, so each such run is a separate Maven invocation, and those two
 * methods alone raise the class timeout, to 60 seconds.
 *
 * <p>Requests are issued through a {@link WebClient}, which aggregates the response body before its
 * future completes (issue #167).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PatternInputBoundIT {

    /**
     * {@code QUADRATIC}: {@code ^\s*\S*\s*\S*$}, quadratic on {@link #worst(int)}, which it does not
     * match; about 78 ms at 4,096 characters.
     */
    static final String QUADRATIC = "^\\s*\\S*\\s*\\S*$";

    /**
     * {@code CATASTROPHIC}: exponential backtracking on {@code "a".repeat(n - 1) + "!"}, about 3 s at 29
     * characters and effectively forever at the lengths used here.
     */
    static final String CATASTROPHIC = "^(a{1,30}){1,30}$";

    /** A linear pattern every all-lowercase value matches. */
    static final String LOWERCASE = "^[a-z]*$";

    /**
     * TP-009's 4,096 half, pinned from the baseline gate (open-core {@code 2f54d2d9}): the {@code errors}
     * the unmodified gate answers {@code {"text": worst(4096)}} with, a single {@code pattern} detail,
     * so the pattern ran.
     */
    private static final JsonArray BASELINE_WORST_CASE_ERRORS = new JsonArray()
            .add(new JsonObject()
                    .put("path", "#/text")
                    .put("detail", "must match pattern: " + QUADRATIC)
                    .put("location", "body")
                    .put("type", "pattern")
                    .put("args", new JsonObject().put("pattern", QUADRATIC)));

    /** TP-009's 4,096 half, pinned from the baseline gate: the status of that answer. */
    private static final int BASELINE_WORST_CASE_STATUS = 400;

    /**
     * TP-016's first half, pinned from the baseline gate (open-core {@code 2f54d2d9}): the status of the
     * pattern-free bulk body.
     */
    private static final int BASELINE_BULK_STATUS = 200;

    /** TP-016's first half, pinned from the baseline gate: the response body of that answer. */
    private static final String BASELINE_BULK_BODY = "stamps=20000 ids=10000";

    /** The bound's default per-string limit. */
    private static final int DEFAULT_MAX_CHARS = 4096;

    /** The bound's default per-request limit. */
    private static final int DEFAULT_MAX_TOTAL_CHARS = 262_144;

    /** Sixteen characters of an {@code a} run: a value fragment no response may contain. */
    private static final String A_RUN = "a".repeat(16);

    /** How long an ordinary request is awaited. */
    private static final long REPLY_SECONDS = 15;

    /**
     * How long a request whose value reaches {@link #CATASTROPHIC} is awaited: long enough that only an
     * evaluation of the pattern can exhaust it, and inside the method's 60-second timeout.
     */
    private static final long CATASTROPHIC_REPLY_SECONDS = 50;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);

    private static final long CLOSE_SECONDS = 15;

    private static final String LOOPBACK = "127.0.0.1";

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
     * Closes the {@link WebClient} and then every server the test started. A server whose event loop
     * is blocked by a pattern evaluation cannot close; its close times out here.
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

    // --- TP-009 ---

    /**
     * TP-009 (AC-020.1). Over HTTP, {@code {"text": worst(4096)}} against {@link #QUADRATIC} is judged
     * exactly as the baseline gate judges it (the pattern ran), and {@code {"text": "a".repeat(4096) +
     * "!"}} against {@link #CATASTROPHIC} is rejected unevaluated with one value-free
     * {@code patternInputLength} detail. Neither resource runs.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("TP-009: over HTTP, 4,096 worst-case characters are judged as today; 4,097 are not evaluated")
    void bodyValueBoundaryOverHttp() throws Exception {
        QuadraticTextResource quadratic = new QuadraticTextResource();
        CatastrophicTextResource catastrophic = new CatastrophicTextResource();
        int port = start(defaultMount(), Set.of(quadratic, catastrophic));

        Reply inBound = post(port, "/quadratic/text", textBody(worst(4096)), REPLY_SECONDS);

        assertAll(
                "AC-020.1: a 4,096-character worst case is judged exactly as the baseline gate judges it",
                () -> assertEquals(BASELINE_WORST_CASE_STATUS, inBound.status(), "body: " + inBound.body()),
                () -> assertEquals(BASELINE_WORST_CASE_ERRORS, inBound.errors(), "body: " + inBound.body()));

        Reply overLong = post(port, "/catastrophic/text", textBody("a".repeat(4096) + "!"), CATASTROPHIC_REPLY_SECONDS);

        assertAll(
                "AC-020.1: a 4,097-character body value is rejected without evaluating the pattern, value-free",
                () -> assertTrue(
                        overLong.contentType().startsWith("application/problem+json"),
                        "content type: " + overLong.contentType()),
                () -> assertSingleBoundDetail(
                        overLong, "", "body", "patternInputLength", "maxChars", DEFAULT_MAX_CHARS),
                () -> assertFalse(overLong.body().contains(A_RUN), "the value leaked: " + abbreviate(overLong.body())),
                () -> assertFalse(overLong.body().contains("{1,30}"), "the pattern leaked: " + overLong.body()),
                () -> assertEquals(0, quadratic.invocations.get(), "the rejected worst case must not run"),
                () -> assertEquals(0, catastrophic.invocations.get(), "the over-long value must not run"));
    }

    // --- TP-010 ---

    /**
     * TP-010 (AC-020.1). Over HTTP, an 8,000-character {@code X-Token} header whose parameter carries
     * {@link #CATASTROPHIC} is rejected unevaluated with one value-free {@code patternInputLength}
     * detail naming the header. The value stays under the server's default 8,192-byte header block so
     * the request reaches the gate.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("TP-010: over HTTP, an over-long patterned header is rejected unevaluated")
    void overLongPatternedHeaderIsRejected() throws Exception {
        PatternedHeaderResource resource = new PatternedHeaderResource();
        int port = start(defaultMount(), Set.of(resource));

        Reply rejected = get(port, "/header/token", "X-Token", "a".repeat(7999) + "!", CATASTROPHIC_REPLY_SECONDS);

        assertAll(
                "AC-020.1: an 8 KiB patterned header is rejected without evaluating the pattern, value-free",
                () -> assertSingleBoundDetail(
                        rejected, "X-Token", "header", "patternInputLength", "maxChars", DEFAULT_MAX_CHARS),
                () -> assertFalse(rejected.body().contains(A_RUN), "the value leaked: " + abbreviate(rejected.body())),
                () -> assertFalse(rejected.body().contains("{1,30}"), "the pattern leaked: " + rejected.body()),
                () -> assertEquals(0, resource.invocations.get(), "the rejected header must not reach the resource"));
    }

    // --- TP-011 ---

    /**
     * TP-011 (AC-020.1). Over HTTP, a 5,000-character key on a case-insensitively bound body, whose
     * generated schema folds member names through {@code patternProperties}, is rejected with one
     * value-free {@code patternInputLength} detail: neither the key nor the generated fold pattern
     * reaches the response.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("TP-011: over HTTP, a 5,000-character key on a case-insensitive body is rejected")
    void overLongKeyOnCaseInsensitiveBodyIsRejected() throws Exception {
        CaseInsensitiveNameResource resource = new CaseInsensitiveNameResource();
        int port = start(defaultMount(), Set.of(resource));

        Reply rejected =
                post(port, "/case-insensitive/name", new JsonObject().put("k".repeat(5000), "v"), REPLY_SECONDS);

        assertAll(
                "AC-020.1: a 5,000-character case-insensitive key is rejected on the bound, value-free",
                () -> assertSingleBoundDetail(
                        rejected, "", "body", "patternInputLength", "maxChars", DEFAULT_MAX_CHARS),
                () -> assertFalse(
                        rejected.body().contains("k".repeat(16)), "the key leaked: " + abbreviate(rejected.body())),
                () -> assertFalse(
                        rejected.body().contains("[nN][aA][mM][eE]"),
                        "the generated fold pattern leaked: " + rejected.body()),
                () -> assertEquals(0, resource.invocations.get(), "the rejected body must not reach the resource"));
    }

    // --- TP-012 ---

    /**
     * TP-012 (AC-020.2). Over HTTP, a body of 100 map values of 4,000 characters, each reaching a
     * {@link #LOWERCASE} pattern position (400,000 characters), is rejected on the per-request total
     * with one value-free {@code patternInputTotalLength} detail, and a body of 10,000 values of eight
     * characters is accepted.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("TP-012: over HTTP, 100 × 4,000 characters fail the total and 10,000 × 8 are accepted")
    void requestTotalOverHttp() throws Exception {
        assertEquals(
                Map.of("/properties/values/additionalProperties/pattern", LOWERCASE),
                schemaKeywords(PatternedValuesBody.class, "pattern"),
                "the Given: every entry value reaches a pattern position, and nothing else does");

        PatternedValuesResource resource = new PatternedValuesResource();
        int port = start(defaultMount(), Set.of(resource));

        Reply overTotal = post(port, "/values/entries", entries(100, "a".repeat(4000)), REPLY_SECONDS);
        Reply manySmall = post(port, "/values/entries", entries(10_000, "abcdefgh"), REPLY_SECONDS);

        assertAll(
                "AC-020.2",
                () -> assertSingleBoundDetail(
                        overTotal, "", "body", "patternInputTotalLength", "maxTotalChars", DEFAULT_MAX_TOTAL_CHARS),
                () -> assertFalse(overTotal.body().contains(A_RUN), "a value leaked: " + abbreviate(overTotal.body())),
                () -> assertEquals(
                        200,
                        manySmall.status(),
                        "10,000 eight-character values (80,000 characters) must be accepted; body: "
                                + abbreviate(manySmall.body())),
                () -> assertEquals(
                        1,
                        resource.invocations.get(),
                        "only the 10,000-entry body may reach the resource, once; the 400,000-character one must"
                                + " not"));
    }

    // --- TP-013 ---

    /**
     * TP-013 (AC-020.2). Limits configured under {@code jaxrs} reach the gate, and invalid ones fail the
     * mount's graph at its {@code JaxRsConfig} provider, naming the failing setting, so no server
     * starts.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("TP-013: configured limits reach the gate, and invalid ones fail startup naming the setting")
    void configuredLimitsApplyAndInvalidOnesFailStartup() throws Exception {
        QuadraticTextResource resource = new QuadraticTextResource();
        RestTestMount configured = MountFixtures.mount(
                vertx,
                jaxrs(new JsonObject().put("validationPatternMaxChars", 16).put("validationPatternMaxTotalChars", 32)),
                RestTestContributions.none());
        int port = start(configured, Set.of(resource));

        Reply seventeen = post(port, "/quadratic/text", textBody("a".repeat(17)), REPLY_SECONDS);
        Reply sixteen = post(port, "/quadratic/text", textBody("a".repeat(16)), REPLY_SECONDS);
        Throwable perStringZero = mountFailure(jaxrs(new JsonObject().put("validationPatternMaxChars", 0)));
        Throwable totalBelowPerString =
                mountFailure(jaxrs(new JsonObject().put("validationPatternMaxTotalChars", 4095)));

        assertAll(
                "AC-020.2",
                () -> assertSingleBoundDetail(seventeen, null, null, "patternInputLength", "maxChars", 16),
                () -> assertEquals(
                        200, sixteen.status(), "the 16-character value must be accepted; body: " + sixteen.body()),
                () -> assertEquals(1, resource.invocations.get(), "only the 16-character value may reach the resource"),
                () -> assertStartupFailure(
                        perStringZero, "jaxrs.validationPatternMaxChars", "jaxrs.validationPatternMaxTotalChars"),
                () -> assertStartupFailure(
                        totalBelowPerString,
                        "jaxrs.validationPatternMaxTotalChars",
                        "jaxrs.validationPatternMaxChars"));
    }

    // --- TP-016 ---

    /**
     * TP-016 (AC-020.4). Over HTTP, a pattern-free body of 20,000 {@code Instant} timestamps
     * ({@code format: date-time}) and 10,000 UUIDs, about 0.85 MB, is answered exactly as the baseline
     * gate answers it, so 760,000 characters at pattern-free positions are neither bounded nor counted;
     * the same body with one 4,097-character {@code @Pattern} string is rejected at that position alone.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("TP-016: a pattern-free bulk body passes; one over-long patterned string alone is rejected")
    void patternFreeBodyIsUnaffectedAndOnePatternedStringIsRejectedAlone() throws Exception {
        assertAll(
                "the Given: pattern-free date-time and uuid positions, and one pattern, at note",
                () -> assertEquals(Map.of(), schemaKeywords(BulkBody.class, "pattern"), "BulkBody's pattern positions"),
                () -> assertEquals(
                        Map.of("/properties/note/pattern", LOWERCASE),
                        schemaKeywords(PatternedBulkBody.class, "pattern"),
                        "PatternedBulkBody's pattern positions"),
                () -> assertEquals(
                        "date-time",
                        schemaKeywords(BulkBody.class, "format").get("/properties/stamps/items/format"),
                        "BulkBody's stamps items"));

        BulkResource plain = new BulkResource();
        PatternedBulkResource patterned = new PatternedBulkResource();
        int port = start(defaultMount(), Set.of(plain, patterned));

        Reply accepted = post(port, "/plain-bulk/body", bulkBody(20_000, 10_000), REPLY_SECONDS);

        assertAll(
                "AC-020.4: 760,000 characters at pattern-free date-time and uuid positions are answered as today",
                () -> assertEquals(BASELINE_BULK_STATUS, accepted.status(), "body: " + abbreviate(accepted.body())),
                () -> assertEquals(BASELINE_BULK_BODY, accepted.body()),
                () -> assertEquals(1, plain.invocations.get(), "the pattern-free body must reach the resource once"));

        Reply rejected = post(
                port, "/patterned-bulk/body", bulkBody(20_000, 10_000).put("note", "a".repeat(4097)), REPLY_SECONDS);

        assertAll(
                "AC-020.4: only the over-long patterned position is rejected; no timestamp or identifier is",
                () -> assertTrue(
                        rejected.contentType().startsWith("application/problem+json"),
                        "content type: " + rejected.contentType()),
                () -> assertSingleBoundDetail(
                        rejected, "", "body", "patternInputLength", "maxChars", DEFAULT_MAX_CHARS),
                () -> assertFalse(rejected.body().contains(A_RUN), "the value leaked: " + abbreviate(rejected.body())),
                () -> assertEquals(0, patterned.invocations.get(), "the rejected body must not reach the resource"));
    }

    // --- Assertions ---

    /**
     * Asserts a 400 whose {@code errors} array holds exactly one bound detail with the given fields.
     *
     * @param reply    the reply
     * @param path     the expected {@code path}, or {@code null} when the proof does not name it
     * @param location the expected {@code location}, or {@code null} when the proof does not name it
     * @param type     the expected {@code type}
     * @param argName  the name of the detail's single argument
     * @param limit    the configured limit the argument carries
     */
    private static void assertSingleBoundDetail(
            Reply reply, String path, String location, String type, String argName, int limit) {
        JsonArray errors = reply.errors();
        JsonObject detail = errors == null || errors.isEmpty() ? new JsonObject() : errors.getJsonObject(0);
        assertAll(
                () -> assertEquals(400, reply.status(), "status; body: " + abbreviate(reply.body())),
                () -> assertEquals(
                        1, errors == null ? -1 : errors.size(), "detail count; body: " + abbreviate(reply.body())),
                () -> {
                    if (path != null) {
                        assertEquals(path, detail.getString("path"), "path");
                    }
                },
                () -> {
                    if (location != null) {
                        assertEquals(location, detail.getString("location"), "location");
                    }
                },
                () -> assertEquals(type, detail.getString("type"), "type"),
                () -> assertEquals(new JsonObject().put(argName, limit), detail.getJsonObject("args"), "args"));
    }

    /**
     * Asserts that building a mount failed with a {@link ConfigurationException} thrown inside
     * {@code RestCoreModule.jaxRsConfig}, the graph's {@code JaxRsConfig} provider, whose message names
     * {@code setting} and not {@code otherSetting}.
     *
     * @param failure      what building the mount threw, or {@code null} when it built
     * @param setting      the setting the message must name
     * @param otherSetting the setting the message must not name
     */
    private static void assertStartupFailure(Throwable failure, String setting, String otherSetting) {
        ConfigurationException configurationFailure = assertInstanceOf(
                ConfigurationException.class,
                failure,
                "a mount configured with an invalid " + setting + " must fail to build");
        String message = configurationFailure.getMessage();
        assertAll(
                () -> assertTrue(message.contains(setting), "message: " + message),
                () -> assertFalse(message.contains(otherSetting), "message: " + message),
                () -> assertTrue(
                        Arrays.stream(configurationFailure.getStackTrace())
                                .anyMatch(frame ->
                                        frame.getClassName().equals("dev.vertique.rest.core.dagger.RestCoreModule")
                                                && frame.getMethodName().equals("jaxRsConfig")),
                        "the failure must come from the graph's JaxRsConfig provider"));
    }

    /**
     * Returns every string value of {@code keyword} in the body schema the mount's schema source
     * generates for {@code dto} under the {@code vertique} floor profile (the profile of every route
     * here), keyed by JSON pointer. A property literally named {@code keyword} is not reported.
     *
     * @param dto     the body type
     * @param keyword the keyword
     * @return the keyword's string values by JSON pointer
     */
    private static Map<String, String> schemaKeywords(Class<?> dto, String keyword) {
        JsonObject schema = new AnnotationSchemaSource()
                .schemasFor(
                        StubDescriptors.builder()
                                .httpMethod("POST")
                                .body(new BodyDescriptor(dto, null, List.of()))
                                .build(),
                        new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique")))
                .bodySchema()
                .orElseThrow();
        Map<String, String> found = new TreeMap<>();
        collectKeyword(schema, "", keyword, found);
        return found;
    }

    private static void collectKeyword(Object node, String pointer, String keyword, Map<String, String> found) {
        if (node instanceof JsonObject object) {
            for (String name : object.fieldNames()) {
                Object value = object.getValue(name);
                String child = pointer + "/" + name.replace("~", "~0").replace("/", "~1");
                if (name.equals(keyword) && value instanceof String text && !pointer.endsWith("/properties")) {
                    found.put(child, text);
                }
                collectKeyword(value, child, keyword, found);
            }
        } else if (node instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                collectKeyword(array.getValue(i), pointer + "/" + i, keyword, found);
            }
        }
    }

    // --- Mounts, requests, and bodies ---

    /**
     * Builds a default-limit mount: every {@code jaxrs}/{@code http} setting at its framework default.
     *
     * @return the mount
     */
    private RestTestMount defaultMount() {
        return MountFixtures.mount(vertx, RestTestContributions.none());
    }

    /**
     * Builds a mount from {@code config} and returns what building it threw.
     *
     * @param config the application configuration
     * @return the failure, or {@code null} when the mount built
     */
    private Throwable mountFailure(JsonObject config) {
        try {
            MountFixtures.mount(vertx, config, RestTestContributions.none());
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    /**
     * Wraps a {@code jaxrs} section into an application configuration.
     *
     * @param section the {@code jaxrs} section
     * @return the configuration
     */
    private static JsonObject jaxrs(JsonObject section) {
        return new JsonObject().put("jaxrs", section);
    }

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

    /**
     * GETs {@code path} with one request header.
     *
     * @param port    the port
     * @param path    the path
     * @param header  the header name
     * @param value   the header value
     * @param seconds how long the reply is awaited
     * @return the reply
     * @throws Exception when the round trip fails or times out
     */
    private Reply get(int port, String path, String header, String value, long seconds) throws Exception {
        return Reply.of(client.get(port, LOOPBACK, path)
                .putHeader(header, value)
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(seconds, TimeUnit.SECONDS));
    }

    /**
     * Returns {@code worst(n) = " ".repeat(n - 5) + "a a a"}, the worst case of {@link #QUADRATIC},
     * which it does not match.
     *
     * @param n the length
     * @return the worst-case input
     */
    private static String worst(int n) {
        return " ".repeat(n - 5) + "a a a";
    }

    private static JsonObject textBody(String text) {
        return new JsonObject().put("text", text);
    }

    /**
     * Builds {@code {"values": {...}}} with {@code count} distinct keys, each mapped to {@code value}.
     *
     * @param count the entry count
     * @param value the value of every entry
     * @return the body
     */
    private static JsonObject entries(int count, String value) {
        JsonObject values = new JsonObject();
        for (int i = 0; i < count; i++) {
            values.put(String.format("k%05d", i), value);
        }
        return new JsonObject().put("values", values);
    }

    /**
     * Builds {@code {"stamps": [...], "ids": [...]}} with {@code stamps} copies of
     * {@code "2026-09-27T12:00:00Z"} and {@code ids} distinct UUIDs {@code new UUID(0, i)}.
     *
     * @param stamps the timestamp count
     * @param ids    the identifier count
     * @return the body
     */
    private static JsonObject bulkBody(int stamps, int ids) {
        JsonArray stampValues = new JsonArray();
        for (int i = 0; i < stamps; i++) {
            stampValues.add("2026-09-27T12:00:00Z");
        }
        JsonArray idValues = new JsonArray();
        for (int i = 0; i < ids; i++) {
            idValues.add(new UUID(0, i).toString());
        }
        return new JsonObject().put("stamps", stampValues).put("ids", idValues);
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

    /** TP-009 and TP-013: a text member carrying {@link #QUADRATIC}. */
    public static class QuadraticTextBody {

        /** The patterned text. */
        @Pattern(regexp = QUADRATIC)
        public String text;
    }

    /** Accepts {@link QuadraticTextBody}, counting invocations. */
    @Path("/quadratic")
    public static class QuadraticTextResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the text's length.
         *
         * @param body the body
         * @return the length
         */
        @POST
        @Path("/text")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundQuadraticText")
        public String text(QuadraticTextBody body) {
            invocations.incrementAndGet();
            return "length=" + body.text.length();
        }
    }

    /** TP-009: a text member carrying {@link #CATASTROPHIC}. */
    public static class CatastrophicTextBody {

        /** The patterned text. */
        @Pattern(regexp = CATASTROPHIC)
        public String text;
    }

    /** Accepts {@link CatastrophicTextBody}, counting invocations. */
    @Path("/catastrophic")
    public static class CatastrophicTextResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the text's length.
         *
         * @param body the body
         * @return the length
         */
        @POST
        @Path("/text")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundCatastrophicText")
        public String text(CatastrophicTextBody body) {
            invocations.incrementAndGet();
            return "length=" + body.text.length();
        }
    }

    /** TP-010: a header parameter carrying {@link #CATASTROPHIC}, counting invocations. */
    @Path("/header")
    public static class PatternedHeaderResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the token's length.
         *
         * @param token the patterned header
         * @return the length
         */
        @GET
        @Path("/token")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundHeader")
        public String token(@HeaderParam("X-Token") @Pattern(regexp = CATASTROPHIC) String token) {
            invocations.incrementAndGet();
            return "length=" + token.length();
        }
    }

    /**
     * TP-011: case-insensitively bound with one {@code String name} member, the {@code
     * F5CaseInsensitiveClosedBody} shape of {@link ProfiledSchemaSynthesisIT}.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static class CaseInsensitiveNameBody {

        /** The one member. */
        public String name;
    }

    /** Accepts {@link CaseInsensitiveNameBody}, counting invocations. */
    @Path("/case-insensitive")
    public static class CaseInsensitiveNameResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the name.
         *
         * @param body the body
         * @return the name
         */
        @POST
        @Path("/name")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundCaseInsensitiveName")
        public String name(CaseInsensitiveNameBody body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }
    }

    /** TP-012: map values each reaching a {@link #LOWERCASE} pattern position. */
    public static class PatternedValuesBody {

        /** The patterned values. */
        public Map<String, @Pattern(regexp = LOWERCASE) String> values;
    }

    /** Accepts {@link PatternedValuesBody}, counting invocations. */
    @Path("/values")
    public static class PatternedValuesResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the entry count.
         *
         * @param body the body
         * @return the entry count
         */
        @POST
        @Path("/entries")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundValues")
        public String entries(PatternedValuesBody body) {
            invocations.incrementAndGet();
            return "entries=" + body.values.size();
        }
    }

    /** TP-016: timestamps and identifiers, and nothing else. */
    public static class BulkBody {

        /** Timestamps ({@code format: date-time}). */
        public List<Instant> stamps;

        /** Identifiers. */
        public List<UUID> ids;
    }

    /** TP-016: {@link BulkBody}'s members and one patterned string. */
    public static class PatternedBulkBody {

        /** Timestamps ({@code format: date-time}). */
        public List<Instant> stamps;

        /** Identifiers. */
        public List<UUID> ids;

        /** The one patterned position. */
        @Pattern(regexp = LOWERCASE)
        public String note;
    }

    /** Accepts {@link BulkBody}, counting invocations. */
    @Path("/plain-bulk")
    public static class BulkResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the member counts.
         *
         * @param body the body
         * @return the counts
         */
        @POST
        @Path("/body")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundBulkPlain")
        public String plain(BulkBody body) {
            invocations.incrementAndGet();
            return "stamps=" + body.stamps.size() + " ids=" + body.ids.size();
        }
    }

    /** Accepts {@link PatternedBulkBody}, counting invocations. */
    @Path("/patterned-bulk")
    public static class PatternedBulkResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the member counts.
         *
         * @param body the body
         * @return the counts
         */
        @POST
        @Path("/body")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternBoundBulkPatterned")
        public String patterned(PatternedBulkBody body) {
            invocations.incrementAndGet();
            return "stamps=" + body.stamps.size() + " ids=" + body.ids.size() + " note=" + body.note.length();
        }
    }
}
