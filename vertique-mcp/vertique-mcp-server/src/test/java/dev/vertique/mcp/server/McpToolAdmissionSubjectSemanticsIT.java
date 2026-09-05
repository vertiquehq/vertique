// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T003 proof of MCP admission's shared subject and anonymous key-derivation contract. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionSubjectSemanticsIT {

    private static final String POLICY_NAME = "subject-semantics";
    private static final String TOOL_NAME = "subject.probe";
    private static final String HTTP_TOOL_NAME = "admission.walkingSkeleton";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private final Vertx vertx = Vertx.vertx();
    private McpToolAdmissionWalkingSkeletonIT.McpToolAdmissionWalkingSkeletonFixture fixture;
    private HttpClient rawClient;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("NONE shares one global bucket across distinct principals")
    void shouldShareOneGlobalBucketAcrossDistinctPrincipalsWhenSubjectIsNone() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AtomicReference<Optional<SecurityIdentity>> identity = new AtomicReference<>(Optional.of(user("principal-1")));
        McpToolAdmission admission =
                admission(RateLimitSubject.NONE, AnonymousRateLimitPolicy.BYPASS, backend, identity, Optional.empty());

        McpToolAdmission.Admission first = await(admission.admit(TOOL_NAME));
        identity.set(Optional.of(user("principal-2")));
        McpToolAdmission.Admission second = await(admission.admit(TOOL_NAME));

        assertThat(first.outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(second.outcome()).isEqualTo(McpToolAdmission.Outcome.QUOTA_EXCEEDED);
        assertThat(backend.requests())
                .extracting(RateLimitBackendRequest::storageKey)
                .containsExactly("subject-semantics:r1:G", "subject-semantics:r1:G");
        assertThat(backend.requests())
                .allSatisfy(request -> assertThat(request.storageKey()).doesNotContain(TOOL_NAME));
    }

    @Test
    @DisplayName("shared anonymous and bypass policies remain distinct for both anonymous representations")
    void shouldDistinguishSharedBucketFromBypassForAnonymousCallers() throws Exception {
        AtomicReference<Optional<SecurityIdentity>> identity = new AtomicReference<>(Optional.empty());

        RecordingBackend sharedBackend = new RecordingBackend();
        McpToolAdmission shared = admission(
                RateLimitSubject.EFFECTIVE_PRINCIPAL,
                AnonymousRateLimitPolicy.SHARED_BUCKET,
                sharedBackend,
                identity,
                Optional.empty());
        McpToolAdmission.Admission sharedFirst = await(shared.admit(TOOL_NAME));
        identity.set(Optional.of(SecurityIdentity.anonymous()));
        McpToolAdmission.Admission sharedSecond = await(shared.admit(TOOL_NAME));

        assertThat(sharedFirst.outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(sharedSecond.outcome()).isEqualTo(McpToolAdmission.Outcome.QUOTA_EXCEEDED);
        assertThat(sharedBackend.requests()).hasSize(2);
        assertThat(sharedBackend.requests().get(0).storageKey())
                .isEqualTo(sharedBackend.requests().get(1).storageKey());

        RecordingBackend bypassBackend = new RecordingBackend();
        identity.set(Optional.empty());
        McpToolAdmission bypass = admission(
                RateLimitSubject.EFFECTIVE_PRINCIPAL,
                AnonymousRateLimitPolicy.BYPASS,
                bypassBackend,
                identity,
                Optional.empty());
        McpToolAdmission.Admission bypassFirst = await(bypass.admit(TOOL_NAME));
        identity.set(Optional.of(SecurityIdentity.anonymous()));
        McpToolAdmission.Admission bypassSecond = await(bypass.admit(TOOL_NAME));

        assertThat(bypassFirst.outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(bypassSecond.outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(bypassBackend.requests()).as("BYPASS must not call acquire").isEmpty();

        RecordingBackend noneBypassBackend = new RecordingBackend();
        McpToolAdmission noneBypass = admission(
                RateLimitSubject.NONE,
                AnonymousRateLimitPolicy.BYPASS,
                noneBypassBackend,
                new AtomicReference<>(Optional.empty()),
                Optional.empty());
        assertThat(await(noneBypass.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(await(noneBypass.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.QUOTA_EXCEEDED);
        assertThat(noneBypassBackend.requests()).hasSize(2);
    }

    @Test
    @DisplayName("CLIENT without a client facet fails before backend acquisition")
    void shouldFailClosedForUnresolvableClientSubjectNeverFallingBackToAnonymous() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        McpToolAdmission admission = admission(
                RateLimitSubject.CLIENT,
                AnonymousRateLimitPolicy.SHARED_BUCKET,
                backend,
                new AtomicReference<>(Optional.of(user("authenticated-without-client"))),
                Optional.empty());

        McpToolAdmission.Admission result = await(admission.admit(TOOL_NAME));

        assertThat(result.outcome()).isEqualTo(McpToolAdmission.Outcome.FAILED);
        assertThat(backend.requests())
                .as("unresolvable CLIENT must never use anonymous fallback")
                .isEmpty();
    }

    @Test
    @DisplayName("unresolvable CLIENT admission produces the bounded rate-limit response")
    void shouldReturnBoundedResponseForUnresolvableClientSubject() throws Exception {
        SecurityIdentity identity = user("authenticated-without-client");
        RecordingBackend backend = new RecordingBackend();
        AtomicReference<Optional<SecurityIdentity>> resolvedIdentity = new AtomicReference<>(Optional.of(identity));
        fixture = McpToolAdmissionWalkingSkeletonIT.McpToolAdmissionWalkingSkeletonFixture.start(
                vertx,
                new McpRateLimitConfig(
                        POLICY_NAME, RateLimitSubject.CLIENT, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()),
                Optional.of(rateLimiters(backend, resolvedIdentity)),
                new FixedIdentityResolver(identity));
        rawClient = vertx.createHttpClient();

        HttpResponse<Buffer> response = await(WebClient.wrap(rawClient)
                .post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", HTTP_TOOL_NAME)
                .sendBuffer(toolCallBody(1)));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonObject error = new JsonObject(response.bodyAsString()).getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(-32022);
        assertThat(error.getString("message")).isEqualTo("Rate limiting unavailable");
        assertThat(backend.requests()).isEmpty();
        assertThat(fixture.toolInvocationCount()).isZero();
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.FAILED, McpErrorType.RATE_LIMIT));
    }

    @Test
    @DisplayName("origin-aware subject modes use captured client IP rather than peer or forwarded text")
    void shouldUseTrustedClientIpForOriginAwareSubjects() throws Exception {
        for (RateLimitSubject subject :
                List.of(RateLimitSubject.IP, RateLimitSubject.ACTOR_OR_IP, RateLimitSubject.CLIENT_OR_IP)) {
            RecordingBackend backend = new RecordingBackend();
            AtomicReference<Optional<SecurityIdentity>> identity = new AtomicReference<>(identityFor(subject));
            AtomicReference<Optional<RequestOrigin>> origin =
                    new AtomicReference<>(Optional.of(origin("203.0.113.77")));
            McpToolAdmission admission =
                    admission(subject, AnonymousRateLimitPolicy.SHARED_BUCKET, backend, identity, origin);

            assertThat(await(admission.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
            origin.set(Optional.of(origin("203.0.113.77", "198.51.100.11")));
            assertThat(await(admission.admit(TOOL_NAME)).outcome())
                    .as("same trusted client IP must share the %s bucket", subject)
                    .isEqualTo(McpToolAdmission.Outcome.QUOTA_EXCEEDED);
            assertThat(backend.requests()).hasSize(2);
            assertThat(backend.requests().get(0).storageKey())
                    .isEqualTo(backend.requests().get(1).storageKey());
        }
    }

    @Test
    @DisplayName("origin-aware modes fail closed when no captured origin is available")
    void shouldFailClosedWhenOriginAwareSubjectHasNoCapturedOrigin() throws Exception {
        for (RateLimitSubject subject :
                List.of(RateLimitSubject.IP, RateLimitSubject.ACTOR_OR_IP, RateLimitSubject.CLIENT_OR_IP)) {
            RecordingBackend backend = new RecordingBackend();
            McpToolAdmission admission = admission(
                    subject,
                    AnonymousRateLimitPolicy.SHARED_BUCKET,
                    backend,
                    new AtomicReference<>(Optional.empty()),
                    Optional.empty());

            assertThat(await(admission.admit(TOOL_NAME)).outcome())
                    .as("missing origin must fail closed for %s", subject)
                    .isEqualTo(McpToolAdmission.Outcome.FAILED);
            assertThat(backend.requests()).isEmpty();
        }

        RecordingBackend validBackend = new RecordingBackend();
        McpToolAdmission valid = admission(
                RateLimitSubject.IP,
                AnonymousRateLimitPolicy.BYPASS,
                validBackend,
                new AtomicReference<>(Optional.empty()),
                Optional.of(origin("203.0.113.77")));
        assertThat(await(valid.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(validBackend.requests()).hasSize(1);
    }

    private McpToolAdmission admission(
            RateLimitSubject subject,
            AnonymousRateLimitPolicy anonymous,
            RecordingBackend backend,
            AtomicReference<Optional<SecurityIdentity>> identity,
            Optional<RequestOrigin> origin) {
        return admission(subject, anonymous, backend, identity, new AtomicReference<>(origin));
    }

    private McpToolAdmission admission(
            RateLimitSubject subject,
            AnonymousRateLimitPolicy anonymous,
            RecordingBackend backend,
            AtomicReference<Optional<SecurityIdentity>> identity,
            AtomicReference<Optional<RequestOrigin>> origin) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        RateLimiters rateLimiters = new RateLimiters(
                Set.of(policy),
                Map.of(RateLimitMode.LOCAL, backend),
                null,
                vertx,
                Set.of(),
                new dev.vertique.ratelimit.spi.RateLimitSubjectResolver() {
                    @Override
                    public Optional<SecurityIdentity> current() {
                        return identity.get();
                    }

                    @Override
                    public Optional<RequestOrigin> currentOrigin() {
                        return origin.get();
                    }
                },
                true);
        McpRateLimitConfig config = new McpRateLimitConfig(POLICY_NAME, subject, anonymous, List.of());
        return McpToolAdmission.create(
                configServer(config), McpToolRegistry.build(Set.of(toolInvoker())), Optional.of(rateLimiters));
    }

    private RateLimiters rateLimiters(RecordingBackend backend, AtomicReference<Optional<SecurityIdentity>> identity) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        return new RateLimiters(
                Set.of(policy),
                Map.of(RateLimitMode.LOCAL, backend),
                null,
                vertx,
                Set.of(),
                new dev.vertique.ratelimit.spi.RateLimitSubjectResolver() {
                    @Override
                    public Optional<SecurityIdentity> current() {
                        return identity.get();
                    }
                },
                true);
    }

    private static McpServerConfig configServer(McpRateLimitConfig rateLimit) {
        return McpServerConfig.builder().rateLimit(rateLimit).build();
    }

    private static McpToolInvoker toolInvoker() {
        McpToolInvoker invoker = mock(McpToolInvoker.class);
        when(invoker.descriptor())
                .thenReturn(new McpToolDescriptor(
                        TOOL_NAME,
                        null,
                        "T003 subject semantics fixture.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\",\"additionalProperties\":false}",
                        null,
                        new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)));
        return invoker;
    }

    private static Optional<SecurityIdentity> identityFor(RateLimitSubject subject) {
        return switch (subject) {
            case IP -> Optional.empty();
            case ACTOR_OR_IP -> Optional.of(SecurityIdentity.anonymous());
            case CLIENT_OR_IP -> Optional.of(user("authenticated-without-client"));
            default -> throw new AssertionError("unexpected origin-aware subject: " + subject);
        };
    }

    private static SecurityIdentity user(String id) {
        return new SecurityIdentity(
                new PrincipalRef(PrincipalType.USER, id, Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RequestOrigin origin(String clientIp) {
        return origin(clientIp, "198.51.100.10");
    }

    private static RequestOrigin origin(String clientIp, String remoteIp) {
        return new RequestOrigin(
                remoteIp,
                44321,
                List.of("203.0.113.77"),
                0,
                false,
                clientIp,
                "https",
                "api.example.test",
                Optional.empty());
    }

    private static Buffer toolCallBody(int requestId) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("_meta", meta).put("name", HTTP_TOOL_NAME))
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class RecordingBackend implements RateLimitBackend {
        private final RateLimitBackend delegate = LocalRateLimitBackendFactory.local(ignored -> 100L, 60_000L);
        private final java.util.concurrent.CopyOnWriteArrayList<RateLimitBackendRequest> requests =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            requests.add(request);
            return delegate.consume(request);
        }

        List<RateLimitBackendRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private record FixedIdentityResolver(SecurityIdentity identity) implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(identity));
        }
    }
}
