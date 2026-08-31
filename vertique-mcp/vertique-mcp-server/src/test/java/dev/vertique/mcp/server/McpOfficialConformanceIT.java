// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.server.support.McpSubprocessHarness;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpContent;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
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
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T027 TP-001 — runs the pinned official conformance runner against exactly MCP-001's nine-id
 * supported server partition. The longer timeout is deliberate: the first bounded runner process
 * installs the exact committed npm lock before executing; later scenario processes reuse that
 * target-local installation.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class McpOfficialConformanceIT {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String PIN_RESOURCE = "mcp/pins/external-pins.json";
    private static final String MANIFEST_RESOURCE = "mcp/conformance/requirements-2026-07-28.yaml";
    private static final String LOCK_RESOURCE = "mcp/conformance/package-lock.json";
    private static final String WRAPPER_RESOURCE = "mcp/conformance/run-conformance.mjs";
    private static final String MANIFEST_SHA256 = "ae2f4f6210fd729e2e318edd5bbfa31a43cee0bc608e48052fa26dbf1d939b57";
    private static final String RUNNER_INTEGRITY =
            "sha512-0V/HZDdWHcg6j0zVBzBsXcPZ571IVi6umKgTpnBhtTx/jm/LONmGF6cIWL2k4Xjyps0OiHV6B37nj2s0pUg0nQ==";
    private static final List<String> EXPECTED_SUPPORTED = List.of(
            "tools-list",
            "tools-call-simple-text",
            "tools-call-error",
            "tools-call-image",
            "tools-call-audio",
            "tools-call-embedded-resource",
            "tools-call-mixed-content",
            "tools-call-with-progress",
            "dns-rebinding-protection");
    private static final Path RETAINED_RESULTS = Path.of("target", "mcp-conformance-results");

    private static ConformanceFixture fixture;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext context) {
        ConformanceFixture.start(vertx).onComplete(context.succeeding(started -> {
            fixture = started;
            context.completeNow();
        }));
    }

    @AfterAll
    static void tearDown(VertxTestContext context) {
        if (fixture == null) {
            context.completeNow();
            return;
        }
        fixture.server().close().onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    @Test
    @DisplayName("shouldReportZeroFailuresForTheSupportedScenarioPartition")
    void shouldReportZeroFailuresForTheSupportedScenarioPartition() throws Exception {
        JsonObject pinLock = new JsonObject(readResource(PIN_RESOURCE));
        byte[] manifest = readResourceBytes(MANIFEST_RESOURCE);
        assertThat(sha256(manifest)).isEqualTo(MANIFEST_SHA256);
        assertThat(pinLock.getJsonObject("manifest").getString("sha256")).isEqualTo(MANIFEST_SHA256);
        assertThat(pinLock.getString("wireRevision")).isEqualTo(PROTOCOL_VERSION);

        List<String> supported = stringList(pinLock.getJsonArray("supportedScenarios"));
        JsonArray deferredValues = pinLock.getJsonArray("deferredScenarios");
        List<String> notScored = stringList(pinLock.getJsonArray("notScoredUpstream"));
        assertThat(deferredValues).hasSize(28).allSatisfy(value -> {
            JsonObject deferred = (JsonObject) value;
            assertThat(deferred.getString("id")).isNotBlank();
            assertThat(deferred.getString("owningSpecification")).matches("MCP-[0-9]{3}");
        });
        assertThat(notScored).hasSize(13);

        ManifestIds manifestIds = parseManifest(manifest);
        Set<String> scored = new LinkedHashSet<>(supported);
        deferredValues.stream()
                .map(JsonObject.class::cast)
                .map(value -> value.getString("id"))
                .forEach(scored::add);
        assertThat(scored).hasSize(37).containsExactlyInAnyOrderElementsOf(manifestIds.serverScored());
        assertThat(supported).containsExactlyElementsOf(EXPECTED_SUPPORTED);
        assertThat(notScored).containsExactlyInAnyOrderElementsOf(manifestIds.serverNotScored());

        assertLockMatchesPin(pinLock);
        prepareRetainedResults();
        Path fixtureRoot = resourcePath("mcp/conformance/package.json").getParent();
        Path wrapper = resourcePath(WRAPPER_RESOURCE);
        McpSubprocessHarness harness = new McpSubprocessHarness(Duration.ofSeconds(60));
        Set<String> executed = new LinkedHashSet<>();

        for (String scenario : supported) {
            fixture.terminalEvents().clear();
            McpSubprocessHarness.Invocation invocation = McpSubprocessHarness.Invocation.of(List.of(
                    "node",
                    wrapper.toString(),
                    "--fixture-root",
                    fixtureRoot.toString(),
                    "--url",
                    fixture.serverUrl(),
                    "--scenario",
                    scenario));

            McpSubprocessHarness.Execution<ScenarioReport> execution =
                    harness.run(invocation, (workspace, result) -> retainReport(workspace, scenario));
            McpSubprocessHarness.Result process = execution.result();
            ScenarioReport report = execution.workspaceValue();

            assertThat(process.timedOut())
                    .as(scenario + " must settle within the harness bound")
                    .isFalse();
            assertThat(process.exitCode())
                    .as(() -> scenario + " runner stderr:\n" + process.stderr())
                    .isZero();
            assertThat(report.checks())
                    .as(scenario + " must execute and score at least one check")
                    .anySatisfy(check -> assertThat(check.getString("status")).isEqualTo("SUCCESS"));
            assertThat(report.checks())
                    .noneSatisfy(check -> assertThat(check.getString("status")).isIn("FAILURE", "WARNING", "SKIPPED"));

            List<McpRequestTerminalEvent> terminalEvents = List.copyOf(fixture.terminalEvents());
            assertThat(terminalEvents)
                    .as(scenario + " must reach the real MCP request lifecycle")
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.protocolVersion()).isEqualTo(PROTOCOL_VERSION));
            retainObservedVersions(report.directory(), terminalEvents);
            executed.add(scenario);
        }

        assertThat(executed).containsExactlyElementsOf(supported);
        assertThat(executed).doesNotContainAnyElementsOf(deferredIds(deferredValues));
        assertThat(executed).doesNotContainAnyElementsOf(notScored);
    }

    private static void assertLockMatchesPin(JsonObject pinLock) {
        JsonObject lock = new JsonObject(readResource(LOCK_RESOURCE));
        JsonObject runner =
                lock.getJsonObject("packages").getJsonObject("node_modules/@modelcontextprotocol/conformance");
        JsonObject pin = pinLock.getJsonArray("artifacts").stream()
                .map(JsonObject.class::cast)
                .filter(value -> "conformance-runner".equals(value.getString("id")))
                .findFirst()
                .orElseThrow();
        assertThat(runner.getString("version"))
                .isEqualTo(pin.getString("version"))
                .isEqualTo("0.2.0-alpha.10");
        assertThat(runner.getString("integrity"))
                .isEqualTo(pin.getString("integrity"))
                .isEqualTo(RUNNER_INTEGRITY);
        assertThat(runner.getString("resolved")).isEqualTo(pin.getString("sourceReference"));
        String wrapper = readResource(WRAPPER_RESOURCE);
        assertThat(wrapper)
                .contains("\"server\"", "\"--url\"", "\"--scenario\"", "\"--spec-version\"", "\"2026-07-28\"");
        assertThat(wrapper).doesNotContain("--requirements", "--expected-failures");
    }

    private static ScenarioReport retainReport(Path workspace, String scenario) throws IOException {
        List<Path> checksFiles;
        try (Stream<Path> paths = Files.walk(workspace.resolve("results"))) {
            checksFiles = paths.filter(path -> path.getFileName().toString().equals("checks.json"))
                    .toList();
        }
        if (checksFiles.size() != 1) {
            throw new IOException("expected one checks.json for " + scenario + ", found " + checksFiles);
        }
        Path sourceDirectory = checksFiles.get(0).getParent();
        Path retainedDirectory =
                RETAINED_RESULTS.resolve(sourceDirectory.getFileName().toString());
        copyDirectory(sourceDirectory, retainedDirectory);
        JsonArray values = new JsonArray(Files.readString(checksFiles.get(0)));
        List<JsonObject> checks = values.stream().map(JsonObject.class::cast).toList();
        return new ScenarioReport(retainedDirectory, checks);
    }

    private static void retainObservedVersions(Path directory, List<McpRequestTerminalEvent> events)
            throws IOException {
        JsonArray observations = new JsonArray();
        events.forEach(event -> observations.add(new JsonObject()
                .put("method", event.method().name())
                .put("toolName", event.toolName())
                .put("outcome", event.outcome().name())
                .put("protocolVersion", event.protocolVersion())));
        Files.writeString(directory.resolve("vertique-observed-protocol-versions.json"), observations.encodePrettily());
    }

    private static void prepareRetainedResults() throws IOException {
        deleteRecursively(RETAINED_RESULTS);
        Files.createDirectories(RETAINED_RESULTS);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Set<String> deferredIds(JsonArray values) {
        Set<String> ids = new LinkedHashSet<>();
        values.stream()
                .map(JsonObject.class::cast)
                .map(value -> value.getString("id"))
                .forEach(ids::add);
        return ids;
    }

    private static List<String> stringList(JsonArray values) {
        return values.stream().map(String.class::cast).toList();
    }

    private static ManifestIds parseManifest(byte[] bytes) {
        Set<String> server = new LinkedHashSet<>();
        Set<String> notScored = new LinkedHashSet<>();
        boolean inServer = false;
        boolean inNotScored = false;
        String candidate = null;
        for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            if (line.equals("server:")) {
                inServer = true;
                continue;
            }
            if (line.equals("client:")) {
                inServer = false;
                continue;
            }
            if (line.equals("not_scored:")) {
                inNotScored = true;
                continue;
            }
            if (inServer && line.startsWith("  - ")) {
                server.add(line.substring(4));
            } else if (inNotScored && line.startsWith("  - scenario: ")) {
                candidate = line.substring("  - scenario: ".length());
            } else if (inNotScored && line.startsWith("    leg: ")) {
                if (line.endsWith("server")) {
                    notScored.add(candidate);
                }
                candidate = null;
            }
        }
        return new ManifestIds(Set.copyOf(server), Set.copyOf(notScored));
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String readResource(String resource) {
        return new String(readResourceBytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String resource) {
        try (InputStream input = McpOfficialConformanceIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError("could not read test resource: " + resource, failure);
        }
    }

    private static Path resourcePath(String resource) {
        try {
            var url = McpOfficialConformanceIT.class.getClassLoader().getResource(resource);
            if (url == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException failure) {
            throw new AssertionError("invalid resource URI: " + resource, failure);
        }
    }

    private record ManifestIds(Set<String> serverScored, Set<String> serverNotScored) {}

    private record ScenarioReport(Path directory, List<JsonObject> checks) {}

    private static final class ConformanceFixture {

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final HttpServer server;
        private final List<McpRequestTerminalEvent> terminalEvents;

        private ConformanceFixture(HttpServer server, List<McpRequestTerminalEvent> terminalEvents) {
            this.server = server;
            this.terminalEvents = terminalEvents;
        }

        static Future<ConformanceFixture> start(Vertx vertx) {
            AtomicReference<Handler<HttpServerRequest>> delegate = new AtomicReference<>();
            HttpServer server = vertx.createHttpServer().requestHandler(request -> {
                Handler<HttpServerRequest> handler = delegate.get();
                if (handler == null) {
                    request.response().setStatusCode(503).end();
                } else {
                    handler.handle(request);
                }
            });
            return server.listen(0, "127.0.0.1").compose(boundServer -> {
                int port = boundServer.actualPort();
                McpServerConfig config = McpServerConfig.builder()
                        .enabled(true)
                        .serverName("vertique-conformance")
                        .serverVersion("1.0")
                        .allowedOrigins(Set.of("http://127.0.0.1:" + port))
                        .build();
                McpToolRegistry registry = McpToolRegistry.build(Set.of(
                        tool("test_simple_text", McpToolResult.text("This is a simple text response for testing.")),
                        tool(
                                "test_error_handling",
                                McpToolResult.error("This tool intentionally returns an error for testing")),
                        tool(
                                "test_image_content",
                                McpToolResult.content(List.of(new McpContent.Image("aGVsbG8=", "image/png")))),
                        tool(
                                "test_audio_content",
                                McpToolResult.content(List.of(new McpContent.Audio("aGVsbG8=", "audio/wav")))),
                        tool(
                                "test_embedded_resource",
                                McpToolResult.content(
                                        List.of(new McpContent.EmbeddedResource(new McpContent.TextResource(
                                                "urn:vertique:conformance", "text/plain", "clear"))))),
                        tool(
                                "test_multiple_content_types",
                                McpToolResult.content(List.of(
                                        new McpContent.Text("mixed"),
                                        new McpContent.Image("aGVsbG8=", "image/png"),
                                        new McpContent.Audio("aGVsbG8=", "audio/wav"),
                                        new McpContent.ResourceLink("https://example.test/source", "source"),
                                        new McpContent.EmbeddedResource(new McpContent.TextResource(
                                                "urn:vertique:mixed", "text/plain", "clear"))))),
                        progressTool()));
                RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
                McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(),
                        new SecurityEventEmitter(Set.of()),
                        NO_OP_CONTEXT_HOLDER,
                        securityRuntime,
                        Optional.empty()));
                HttpConfig httpConfig =
                        HttpConfig.builder().idleTimeoutSeconds(60).build();
                List<McpRequestTerminalEvent> events = new CopyOnWriteArrayList<>();
                McpRequestLifecycleObserver observer = startedAt -> new McpRequestObservation() {
                    @Override
                    public void onTerminal(McpRequestTerminalObservation observation) {
                        events.add(observation.event());
                    }
                };
                McpRouterMount mount = new McpRouterMount(
                        config,
                        new McpServerConfigValidator(),
                        new McpRequestDispatcher(
                                config,
                                securityRuntime,
                                Set.of(observer),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                httpConfig,
                                registry,
                                policyEnforcer,
                                NO_OP_CONTEXT_HOLDER,
                                new CorrelationContextFactory(Optional.empty())),
                        Set.of(),
                        identityResolution(securityRuntime),
                        httpConfig,
                        registry);
                return mount.createRouter(vertx).map(mcpRouter -> {
                    Router router = Router.router(vertx);
                    router.route().handler(new RequestContextLifecycle());
                    router.route(config.mountPath()).subRouter(mcpRouter);
                    delegate.set(router);
                    return new ConformanceFixture(boundServer, events);
                });
            });
        }

        private static McpToolInvoker tool(String name, McpToolResult<?> result) {
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "Official conformance fixture tool " + name,
                    ANNOTATIONS,
                    CLOSED_OBJECT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            return new McpToolInvoker() {
                @Override
                public McpToolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                    return new McpPreparedToolCall() {
                        @Override
                        public Map<String, Object> normalizedArguments() {
                            return Map.of();
                        }

                        @Override
                        public Future<McpToolResult<?>> invoke() {
                            return Future.succeededFuture(result);
                        }
                    };
                }
            };
        }

        private static McpToolInvoker progressTool() {
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    "test_tool_with_progress",
                    null,
                    "Official conformance fixture tool test_tool_with_progress",
                    ANNOTATIONS,
                    CLOSED_OBJECT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            return new McpToolInvoker() {
                @Override
                public McpToolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                    return new McpPreparedToolCall() {
                        @Override
                        public Map<String, Object> normalizedArguments() {
                            return Map.of();
                        }

                        @Override
                        public Future<McpToolResult<?>> invoke() {
                            return cancellation
                                    .progressReporter()
                                    .report(0, 100.0, "starting")
                                    .compose(ignored ->
                                            cancellation.progressReporter().report(50, 100.0, "working"))
                                    .compose(ignored ->
                                            cancellation.progressReporter().report(100, 100.0, "complete"))
                                    .map(ignored -> McpToolResult.text("progress complete"));
                        }
                    };
                }
            };
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        HttpServer server() {
            return server;
        }

        String serverUrl() {
            return "http://127.0.0.1:" + server.actualPort() + "/mcp/";
        }

        List<McpRequestTerminalEvent> terminalEvents() {
            return terminalEvents;
        }
    }

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

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
