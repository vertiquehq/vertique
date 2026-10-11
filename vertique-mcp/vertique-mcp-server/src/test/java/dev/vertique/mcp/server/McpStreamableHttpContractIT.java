// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T004 Streamable-HTTP protocol matrix through the real plain Vert.x MCP mount.
 *
 * <p>Each row drives exactly one HTTP request — varying method, {@code Origin}, {@code Accept},
 * body size, and unsupported session headers — against a port-0 mount with no registered tools, no
 * authentication scheme, and a single allowed origin. The decisive row proves the missing
 * origin-admission behavior: a present but disallowed {@code Origin} must be rejected with HTTP 403,
 * where the current unhardened mount performs no origin check and answers 200.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves, while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpStreamableHttpContractIT {

    private static final String VALID_POST_NO_ORIGIN_ROW = "shouldNegotiateJsonForValidPostWithoutOrigin";
    private static final String VALID_POST_ALLOWED_ORIGIN_ROW = "shouldNegotiateJsonForValidPostWithAllowedOrigin";
    private static final String DISALLOWED_ORIGIN_ROW = "shouldRejectDisallowedOriginWithForbidden";
    private static final String GET_ROW = "shouldRejectGetWithMethodNotAllowed";
    private static final String DELETE_ROW = "shouldRejectDeleteWithMethodNotAllowed";
    private static final String JSON_ACCEPT_ROW = "shouldAcceptJsonOnlyAcceptHeader";
    private static final String EVENT_STREAM_ACCEPT_ROW = "shouldAcceptEventStreamAcceptHeader";
    private static final String INVALID_CONTENT_TYPE_ROW = "shouldRejectNonJsonContentTypeWithUnsupportedMediaType";
    private static final String INVALID_ACCEPT_ROW = "shouldRejectUnacceptableAcceptWithNotAcceptable";
    private static final String ZERO_QUALITY_ACCEPT_ROW = "shouldRejectZeroQualityAcceptWithNotAcceptable";
    private static final String ZERO_QUALITY_MIXED_ACCEPT_ROW = "shouldRejectZeroQualityMixedAcceptWithNotAcceptable";
    private static final String WILDCARD_ACCEPT_ROW = "shouldAcceptAnyMediaRangeAcceptHeader";
    private static final String WILDCARD_TYPE_CONCRETE_SUBTYPE_ACCEPT_ROW =
            "shouldRejectWildcardTypeWithConcreteSubtypeAcceptWithNotAcceptable";
    private static final String APPLICATION_WILDCARD_ACCEPT_ROW = "shouldAcceptApplicationWildcardAcceptHeader";
    private static final String TEXT_WILDCARD_ACCEPT_ROW = "shouldAcceptTextWildcardAcceptHeader";
    private static final String JSON_ZERO_QUALITY_APPLICATION_WILDCARD_ACCEPT_ROW =
            "shouldRejectZeroQualityJsonBesideApplicationWildcardWithNotAcceptable";
    private static final String ZERO_QUALITY_WILDCARD_ACCEPT_ROW = "shouldRejectZeroQualityWildcardWithNotAcceptable";
    private static final String BLANK_ACCEPT_ROW = "shouldRejectBlankAcceptWithNotAcceptable";
    private static final String QUOTED_COMMA_ZERO_QUALITY_ACCEPT_ROW =
            "shouldRejectZeroQualityAfterQuotedCommaWithNotAcceptable";
    private static final String ESCAPED_QUOTE_ZERO_QUALITY_ACCEPT_ROW =
            "shouldRejectZeroQualityAfterEscapedQuoteWithNotAcceptable";
    private static final String UNTERMINATED_QUOTE_ZERO_QUALITY_ACCEPT_ROW =
            "shouldRejectUnterminatedQuoteBeforeZeroQualityWithNotAcceptable";
    private static final String UNTERMINATED_QUOTE_ACCEPT_ROW = "shouldRejectUnterminatedQuoteWithNotAcceptable";
    private static final String INVALID_QUALITY_ACCEPT_ROW = "shouldRejectInvalidQualityWithNotAcceptable";
    private static final String QUOTED_COMMA_ACCEPT_ROW = "shouldAcceptQuotedCommaParameterWithoutZeroQuality";
    private static final String QUOTED_ZERO_QUALITY_TEXT_ACCEPT_ROW = "shouldAcceptZeroQualityTextInsideQuotedValue";
    private static final String OVERSIZED_BODY_ROW = "shouldRejectOversizedBodyWithBoundedStatus";
    private static final String SESSION_HEADER_ROW = "shouldIgnoreUnsupportedSessionHeaderRemainingBounded";

    private static final String ALLOWED_ORIGIN = "https://allowed.example";
    private static final String DISALLOWED_ORIGIN = "https://evil.example";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private final Vertx vertx = Vertx.vertx();

    private McpContractFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t004ContractRows() {
        return Stream.of(
                VALID_POST_NO_ORIGIN_ROW,
                VALID_POST_ALLOWED_ORIGIN_ROW,
                DISALLOWED_ORIGIN_ROW,
                GET_ROW,
                DELETE_ROW,
                JSON_ACCEPT_ROW,
                EVENT_STREAM_ACCEPT_ROW,
                INVALID_CONTENT_TYPE_ROW,
                INVALID_ACCEPT_ROW,
                ZERO_QUALITY_ACCEPT_ROW,
                ZERO_QUALITY_MIXED_ACCEPT_ROW,
                WILDCARD_ACCEPT_ROW,
                WILDCARD_TYPE_CONCRETE_SUBTYPE_ACCEPT_ROW,
                APPLICATION_WILDCARD_ACCEPT_ROW,
                TEXT_WILDCARD_ACCEPT_ROW,
                JSON_ZERO_QUALITY_APPLICATION_WILDCARD_ACCEPT_ROW,
                ZERO_QUALITY_WILDCARD_ACCEPT_ROW,
                BLANK_ACCEPT_ROW,
                QUOTED_COMMA_ZERO_QUALITY_ACCEPT_ROW,
                ESCAPED_QUOTE_ZERO_QUALITY_ACCEPT_ROW,
                UNTERMINATED_QUOTE_ZERO_QUALITY_ACCEPT_ROW,
                UNTERMINATED_QUOTE_ACCEPT_ROW,
                INVALID_QUALITY_ACCEPT_ROW,
                QUOTED_COMMA_ACCEPT_ROW,
                QUOTED_ZERO_QUALITY_TEXT_ACCEPT_ROW,
                OVERSIZED_BODY_ROW,
                SESSION_HEADER_ROW);
    }

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback so no in-flight request meets a closed pool.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t004ContractRows")
    @DisplayName("T004 protocol matrix: method, origin, media/Accept, body, and session admission")
    void shouldRejectGetDeleteMediaAcceptAndInvalidOrigin(String row) throws Exception {
        fixture = McpContractFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        JsonObject discover = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", discoverParams());

        switch (row) {
            case VALID_POST_NO_ORIGIN_ROW -> {
                // Given: a well-formed discovery POST with no Origin header at all.
                HttpResponse<Buffer> response = await(post().sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case VALID_POST_ALLOWED_ORIGIN_ROW -> {
                // Given: a discovery POST whose Origin is the one configured allowed origin.
                HttpRequest<Buffer> request = post().putHeader("Origin", ALLOWED_ORIGIN);
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case DISALLOWED_ORIGIN_ROW -> {
                // Given: a discovery POST whose Origin is not in the allowed set.
                // DECISIVE: the contract requires a present, disallowed Origin to be HTTP 403; the
                // current mount performs no origin check and answers 200, so this row is red.
                HttpRequest<Buffer> request = post().putHeader("Origin", DISALLOWED_ORIGIN);
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a present, disallowed Origin must be rejected with HTTP 403 before dispatch")
                        .isEqualTo(403);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case GET_ROW -> {
                // Given: a GET against the mount, which the stateless protocol never accepts.
                HttpResponse<Buffer> response =
                        await(client.get(fixture.port(), "127.0.0.1", McpContractFixture.REQUEST_PATH)
                                .send());

                assertThat(response.statusCode()).isEqualTo(405);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case DELETE_ROW -> {
                // Given: a DELETE against the mount, which the stateless protocol never accepts.
                HttpResponse<Buffer> response =
                        await(client.delete(fixture.port(), "127.0.0.1", McpContractFixture.REQUEST_PATH)
                                .send());

                assertThat(response.statusCode()).isEqualTo(405);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case JSON_ACCEPT_ROW -> {
                // Given: a discovery POST that accepts only application/json.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case EVENT_STREAM_ACCEPT_ROW -> {
                // Given: a discovery POST that accepts both JSON and SSE; discovery always answers JSON.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json, text/event-stream");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case INVALID_CONTENT_TYPE_ROW -> {
                // Given: a discovery POST whose Content-Type is not application/json.
                // The present-only media admission (W5) rejects it with HTTP 415 before the coordinator
                // is created; pre-fix production performs no content-type check and answers 200.
                HttpRequest<Buffer> request = post().putHeader("content-type", "text/plain");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a non-JSON Content-Type must be rejected with HTTP 415 before dispatch")
                        .isEqualTo(415);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case INVALID_ACCEPT_ROW -> {
                // Given: a discovery POST whose Accept admits none of the allowed media ranges.
                // The present-only media admission (W5) rejects it with HTTP 406 before the coordinator
                // is created; pre-fix production performs no Accept check and answers 200.
                HttpRequest<Buffer> request = post().putHeader("Accept", "text/plain");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("an Accept that admits no allowed media range must be rejected with HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case ZERO_QUALITY_ACCEPT_ROW -> {
                // Given: a discovery POST whose only Accept range explicitly rejects application/json
                // with q=0. Per RFC 7231 a q=0 range is not acceptable, so the request is HTTP 406;
                // pre-fix production strips the q parameter and admits it with 200.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;q=0");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("an Accept range with q=0 does not admit its media type and must be HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case ZERO_QUALITY_MIXED_ACCEPT_ROW -> {
                // Given: a discovery POST whose only supported range carries q=0 and whose other range
                // is unsupported; no range admits, so the request is HTTP 406.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;q=0, text/plain");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("when every matching Accept range carries q=0 and no other admits, HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case WILDCARD_ACCEPT_ROW -> {
                // Given: a discovery POST that accepts any media type, which
                // makes application/json acceptable.
                HttpRequest<Buffer> request = post().putHeader("Accept", "*/*");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case WILDCARD_TYPE_CONCRETE_SUBTYPE_ACCEPT_ROW -> {
                // Given: a discovery POST whose only Accept range has a wildcard type and a concrete
                // subtype. RFC 9110 defines no such range, so it is malformed and admits nothing.
                HttpRequest<Buffer> request = post().putHeader("Accept", "*/json");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a wildcard type with a concrete subtype is not a media range and must be HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case APPLICATION_WILDCARD_ACCEPT_ROW -> {
                // Given: a discovery POST that accepts every application/* type, which
                // makes application/json acceptable.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/*");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case TEXT_WILDCARD_ACCEPT_ROW -> {
                // Given: a discovery POST that accepts every text/* type; that range covers
                // text/event-stream, so the request is admitted.
                HttpRequest<Buffer> request = post().putHeader("Accept", "text/*");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case JSON_ZERO_QUALITY_APPLICATION_WILDCARD_ACCEPT_ROW -> {
                // Given: application/json is explicitly excluded with q=0 and application/* does not
                // cover text/event-stream, so the more specific q=0 entry leaves nothing acceptable.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;q=0, application/*");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as(
                                "a q=0 JSON entry must not be re-admitted through application/* when nothing else is acceptable")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case ZERO_QUALITY_WILDCARD_ACCEPT_ROW -> {
                // Given: a discovery POST whose only range, */*, carries q=0, so neither JSON nor
                // event-stream is acceptable.
                HttpRequest<Buffer> request = post().putHeader("Accept", "*/*;q=0");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a q=0 wildcard admits nothing and must be HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case BLANK_ACCEPT_ROW -> {
                // Given: a discovery POST whose Accept header is present but blank, which names no
                // acceptable media type.
                HttpRequest<Buffer> request = post().putHeader("Accept", " ");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a blank Accept is present but admits nothing and must be HTTP 406")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case QUOTED_COMMA_ZERO_QUALITY_ACCEPT_ROW -> {
                // Given: a q=0 range whose quoted profile parameter contains a comma. The comma is part of
                // the quoted value, not a range separator, so the q=0 still belongs to the JSON range.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"a,b\";q=0");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a quoted comma must not hide the q=0 that rejects the range")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case ESCAPED_QUOTE_ZERO_QUALITY_ACCEPT_ROW -> {
                // Given: a q=0 range whose quoted parameter holds a backslash-escaped quote before a
                // comma; the escaped quote must not end the quoted string early.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"a\\\"b,c\";q=0");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("an escaped quote must not end the quoted string and expose the comma")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case UNTERMINATED_QUOTE_ZERO_QUALITY_ACCEPT_ROW -> {
                // Given: a range whose quoted parameter never closes, so the q=0 after it is inside the
                // unterminated string. The malformed range is skipped and admits nothing.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"x;q=0");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a range with an unterminated quoted string is malformed and must not admit JSON")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case UNTERMINATED_QUOTE_ACCEPT_ROW -> {
                // Given: a JSON range with an unterminated quoted string and no q=0 anywhere. It is
                // malformed, so it is skipped rather than read leniently, and nothing else admits.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"x");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a malformed range must not admit JSON even without a q=0")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case INVALID_QUALITY_ACCEPT_ROW -> {
                // Given: a JSON range whose q is not a valid qvalue. The range is malformed and skipped,
                // never treated as q=1, so with no other range the request is HTTP 406.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;q=abc");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a range with an invalid q must not be admitted as if it were q=1")
                        .isEqualTo(406);
                assertNoToolInvoked();
                assertNoObservationOpened();
            }
            case QUOTED_COMMA_ACCEPT_ROW -> {
                // Given: the same quoted-comma parameter with no q=0, so the range admits JSON.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"a,b\"");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case QUOTED_ZERO_QUALITY_TEXT_ACCEPT_ROW -> {
                // Given: "q=0" appearing only inside a quoted parameter value, which is data and not a
                // quality parameter, so the range still admits JSON.
                HttpRequest<Buffer> request = post().putHeader("Accept", "application/json;profile=\"x;q=0;y\"");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertServerInfo(response);
                assertNoToolInvoked();
            }
            case OVERSIZED_BODY_ROW -> {
                // Given: a body larger than the configured maxBodySize.
                Buffer oversized = Buffer.buffer(new byte[McpContractFixture.MAX_BODY_BYTES + 1_024]);
                HttpResponse<Buffer> response = await(post().sendBuffer(oversized));

                assertThat(response.statusCode())
                        .as("an oversized body is a bounded HTTP failure, not a discovery result")
                        .isBetween(400, 499);
                assertNoToolInvoked();
            }
            case SESSION_HEADER_ROW -> {
                // Given: an unsupported session header on an otherwise valid discovery POST.
                // The stateless protocol has no session concept; the contract does not pin a status for
                // a stray session header, so this row only asserts a bounded response and no tool call.
                HttpRequest<Buffer> request = post().putHeader("Mcp-Session-Id", "should-be-ignored");
                HttpResponse<Buffer> response = await(request.sendBuffer(discover.toBuffer()));

                assertThat(response.statusCode()).isBetween(200, 499);
                assertNoToolInvoked();
            }
            default -> fail("unknown T004 contract row: " + row);
        }
    }

    private HttpRequest<Buffer> post() {
        return client.post(fixture.port(), "127.0.0.1", McpContractFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover");
    }

    /**
     * Builds a schema-valid {@code params._meta} for a {@code server/discover} frame, carrying the
     * candidate protocol version and an empty client-capabilities object — both members are required
     * by the vendored {@code RequestMetaObject} definition of {@code mcp/schema/2026-07-28}.
     */
    private static JsonObject discoverParams() {
        return new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
    }

    private void assertNoToolInvoked() {
        assertThat(fixture.terminalMethods())
                .as("no row in this matrix may reach a tools/call terminal")
                .doesNotContain(McpMethod.TOOLS_CALL);
    }

    /**
     * Proves the W4 ordering: an admission rejection settles before the completion coordinator is
     * created, so no lifecycle observation is ever opened — consistent with the body-limit path. The
     * observer's {@code open} is called synchronously inside the coordinator constructor, so by the
     * time the client has the response any observation that was going to open already has.
     */
    private void assertNoObservationOpened() {
        assertThat(fixture.observationsOpened())
                .as("an admission rejection fires before the coordinator is created and opens no observation")
                .isZero();
        assertThat(fixture.terminalMethods())
                .as("an admission rejection that opens no observation delivers no terminal")
                .isEmpty();
    }

    private static void assertServerInfo(HttpResponse<Buffer> response) {
        JsonObject body = new JsonObject(response.bodyAsString());
        JsonObject serverInfo =
                body.getJsonObject("result").getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo");
        assertThat(serverInfo.getString("name")).isEqualTo(SERVER_NAME);
        assertThat(serverInfo.getString("version")).isEqualTo(SERVER_VERSION);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts the minimal MCP mount the protocol matrix exercises: no tools, no
     * authentication scheme, a single allowed origin, the real {@link IdentityResolutionMiddleware},
     * a small body limit, and a recording observer that captures each terminal method.
     */
    private static final class McpContractFixture {

        /** Path under the {@code /mcp/*} mount every row posts to. */
        static final String REQUEST_PATH = "/mcp/";

        /** A deliberately small body limit so the oversized-body row is cheap to trigger. */
        static final int MAX_BODY_BYTES = 64 * 1_024;

        private final List<McpMethod> terminalMethods = new CopyOnWriteArrayList<>();
        private final AtomicInteger observationsOpened = new AtomicInteger();
        private final HttpServer server;
        private final int port;

        private McpContractFixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .allowedOrigins(Set.of(ALLOWED_ORIGIN))
                    .build();
            AtomicReference<SecurityContext> bound = new AtomicReference<>();
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(bound);
            HttpConfig httpConfig = HttpConfig.builder()
                    .maxBodySize(MAX_BODY_BYTES)
                    .idleTimeoutSeconds(60)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of());
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(recordingObserver()),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                                    Optional.empty(),
                                    Optional.empty(),
                                    Set.of(),
                                    new SecurityEventEmitter(Set.of()),
                                    NO_OP_CONTEXT_HOLDER,
                                    securityRuntime,
                                    Optional.empty(),
                                    Resilience.create(vertx))),
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpContractFixture start(Vertx vertx) throws Exception {
            return new McpContractFixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        List<McpMethod> terminalMethods() {
            return terminalMethods;
        }

        int observationsOpened() {
            return observationsOpened.get();
        }

        private McpRequestLifecycleObserver recordingObserver() {
            return startedAt -> {
                observationsOpened.incrementAndGet();
                return new McpRequestObservation() {
                    @Override
                    public void onTerminal(McpRequestTerminalObservation observation) {
                        terminalMethods.add(observation.event().method());
                    }
                };
            };
        }

        private IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence and the {@code sub} user otherwise. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} for dispatch to snapshot. */
    private record RecordingSecurityRuntime(AtomicReference<SecurityContext> bound) implements SecurityRuntime {

        @Override
        public SecurityContext current() {
            return bound.get();
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound.set(context);
            return () -> {};
        }

        @Override
        public void clearCurrent() {
            bound.set(null);
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
