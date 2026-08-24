// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.support.McpSubprocessHarness;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** T029 TP-001 — pinned stable Go client interoperability over Streamable HTTP. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class McpGoClientInteropIT {

    private static final String ANONYMOUS_ROW = "shouldDiscoverListAndCallAsAnonymous";
    private static final String BEARER_ROW = "shouldCallTheRestrictedToolAsBearerAlice";
    private static final String INVALID_BEARER_ROW = "shouldFailInvalidBearerWithoutAnonymousDowngrade";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SDK_VERSION = "v1.7.0";
    private static final String SDK_CHECKSUM = "h1:yqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44=";
    private static final String PIN_RESOURCE = "mcp/pins/external-pins.json";
    private static final String MODULE_RESOURCE = "mcp/clients/go/go.mod";
    private static final String SUM_RESOURCE = "mcp/clients/go/go.sum";
    private static final String CLIENT_RESOURCE = "mcp/clients/go/client.go";

    private static McpGoClientInteropITFixture fixture;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext context) {
        assertLockMatchesPin();
        McpGoClientInteropITFixture.start(vertx).onComplete(context.succeeding(started -> {
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

    private static Stream<String> t029ContractRows() {
        return Stream.of(ANONYMOUS_ROW, BEARER_ROW, INVALID_BEARER_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t029ContractRows")
    @DisplayName("T029 stable Go client interoperability matrix")
    void shouldEnforceT029ContractMatrix(String row) throws Exception {
        fixture.resetObservations();

        Path fixtureDirectory = resourcePath(CLIENT_RESOURCE).getParent();
        McpSubprocessHarness.Invocation invocation = McpSubprocessHarness.Invocation.of(List.of(
                        goExecutable(),
                        "-C",
                        fixtureDirectory.toString(),
                        "run",
                        "-mod=readonly",
                        ".",
                        "--url",
                        fixture.serverUrl(),
                        "--scenario",
                        row))
                .withSensitiveValues(Set.of(McpGoClientInteropITFixture.BEARER_ALICE, "Bearer invalid"));
        McpSubprocessHarness.Result process =
                new McpSubprocessHarness(Duration.ofSeconds(60)).run(invocation).result();

        assertThat(process.timedOut())
                .as(row + " must settle within the process bound")
                .isFalse();
        assertThat(process.exitCode())
                .as(() -> row + " client stderr:\n" + process.stderr())
                .isZero();
        assertThat(process.normalExitCount()).isEqualTo(1);
        assertThat(process.forcedTerminationCount()).isZero();
        assertThat(process.workspace()).doesNotExist();
        assertThat(process.stdout()).doesNotContain(McpGoClientInteropITFixture.BEARER_ALICE, "Bearer invalid");
        assertThat(process.stderr()).doesNotContain(McpGoClientInteropITFixture.BEARER_ALICE, "Bearer invalid");

        JsonObject report = resultReport(process.stdout());
        assertThat(report.getString("scenario")).isEqualTo(row);
        switch (row) {
            case ANONYMOUS_ROW ->
                assertSuccessfulCall(
                        report,
                        McpGoClientInteropITFixture.PUBLIC_TOOL,
                        McpGoClientInteropITFixture.PUBLIC_RESULT,
                        List.of(McpGoClientInteropITFixture.PUBLIC_TOOL));
            case BEARER_ROW ->
                assertSuccessfulCall(
                        report,
                        McpGoClientInteropITFixture.RESTRICTED_TOOL,
                        McpGoClientInteropITFixture.RESTRICTED_RESULT,
                        List.of(McpGoClientInteropITFixture.PUBLIC_TOOL, McpGoClientInteropITFixture.RESTRICTED_TOOL));
            case INVALID_BEARER_ROW -> assertInvalidBearerFailure(report);
            default -> throw new AssertionError("unrecognized T029 row: " + row);
        }
    }

    private static void assertSuccessfulCall(
            JsonObject report, String expectedTool, String expectedText, List<String> expectedVisibleTools) {
        assertThat(report.getString("negotiatedProtocolVersion")).isEqualTo(PROTOCOL_VERSION);
        assertThat(report.getJsonArray("toolNames").stream().map(String.class::cast))
                .containsExactlyInAnyOrderElementsOf(expectedVisibleTools);
        assertThat(report.getString("calledTool")).isEqualTo(expectedTool);
        assertThat(report.getString("resultText")).isEqualTo(expectedText);
        assertThat(report.getBoolean("resultIsError", false)).isFalse();

        if (McpGoClientInteropITFixture.PUBLIC_TOOL.equals(expectedTool)) {
            assertThat(fixture.publicInvocations().get()).isEqualTo(1);
            assertThat(fixture.restrictedInvocations().get()).isZero();
            assertThat(fixture.absentCredentials().get()).isPositive();
            assertThat(fixture.validCredentials().get()).isZero();
        } else {
            assertThat(fixture.publicInvocations().get()).isZero();
            assertThat(fixture.restrictedInvocations().get()).isEqualTo(1);
            assertThat(fixture.absentCredentials().get()).isZero();
            assertThat(fixture.validCredentials().get()).isPositive();
        }
        assertThat(fixture.invalidCredentials().get()).isZero();
    }

    private static void assertInvalidBearerFailure(JsonObject report) {
        assertThat(report.getBoolean("failed")).isTrue();
        assertThat(report.getString("errorMessage")).isNotBlank();
        assertThat(fixture.invalidCredentials().get()).isPositive();
        assertThat(fixture.absentCredentials().get())
                .as("the client must not retry the invalid credential as anonymous")
                .isZero();
        assertThat(fixture.validCredentials().get()).isZero();
        assertThat(fixture.publicInvocations().get()).isZero();
        assertThat(fixture.restrictedInvocations().get()).isZero();
    }

    private static void assertLockMatchesPin() {
        JsonObject pinLock = new JsonObject(readResource(PIN_RESOURCE));
        JsonObject clientPin = pinLock.getJsonArray("artifacts").stream()
                .map(JsonObject.class::cast)
                .filter(value -> "go-sdk".equals(value.getString("id")))
                .findFirst()
                .orElseThrow();
        assertThat(clientPin.getString("version")).isEqualTo(SDK_VERSION);
        assertThat(clientPin.getString("integrity")).isEqualTo(SDK_CHECKSUM);

        assertThat(readResource(MODULE_RESOURCE)
                        .lines()
                        .filter(line -> line.startsWith("require github.com/modelcontextprotocol/go-sdk ")))
                .singleElement()
                .isEqualTo("require github.com/modelcontextprotocol/go-sdk " + SDK_VERSION);
        assertThat(readResource(SUM_RESOURCE)
                        .lines()
                        .filter(line -> line.startsWith("github.com/modelcontextprotocol/go-sdk ")))
                .containsExactly(
                        "github.com/modelcontextprotocol/go-sdk " + SDK_VERSION + " " + SDK_CHECKSUM,
                        "github.com/modelcontextprotocol/go-sdk " + SDK_VERSION
                                + "/go.mod h1:dL7u98E/zjJTGzEq+j30jQ8K2k1mb6LeAH4inEcSGts=");
    }

    private static JsonObject resultReport(String output) {
        String prefix = "T029_RESULT=";
        List<String> reports = output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .toList();
        assertThat(reports)
                .as("the Go fixture must emit exactly one result report")
                .singleElement();
        return new JsonObject(reports.getFirst());
    }

    private static String goExecutable() {
        return System.getProperty("mcp.go.executable", "go");
    }

    private static String readResource(String resource) {
        try (InputStream input = McpGoClientInteropIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("could not read test resource: " + resource, failure);
        }
    }

    private static Path resourcePath(String resource) {
        try {
            var url = McpGoClientInteropIT.class.getClassLoader().getResource(resource);
            if (url == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException failure) {
            throw new AssertionError("invalid resource URI: " + resource, failure);
        }
    }
}
