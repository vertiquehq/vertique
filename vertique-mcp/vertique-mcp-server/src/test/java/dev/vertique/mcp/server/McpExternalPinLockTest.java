// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves the frozen external versions, integrity values, invocation, and scenario partition. */
class McpExternalPinLockTest {

    private static final String PIN_RESOURCE = "mcp/pins/external-pins.json";
    private static final String MANIFEST_RESOURCE = "mcp/conformance/requirements-2026-07-28.yaml";
    private static final String MANIFEST_SHA256 = "ae2f4f6210fd729e2e318edd5bbfa31a43cee0bc608e48052fa26dbf1d939b57";
    private static final String ENTRY_ROW = "shouldRecordAnEntryForEveryRequiredExternalArtifact";
    private static final String VERSION_ROW = "shouldPinExactVersionsWithoutRangesExceptTheRecordedAnchorPrerelease";
    private static final String INTEGRITY_ROW = "shouldCarryAResolvableIntegrityOrChecksumValuePerEntry";
    private static final String INVOCATION_ROW = "shouldFreezeAPerScenarioInvocationWithNoRequirementsFlag";
    private static final String DIGEST_ROW = "shouldVerifyTheCommittedManifestCopyDigestBeforeComparing";
    private static final String PARTITION_ROW = "shouldMatchTheCommittedManifestServerLegIdForId";

    private static final JsonObject PIN_LOCK = new JsonObject(readResource(PIN_RESOURCE));
    private static final List<JsonObject> ARTIFACTS = PIN_LOCK.getJsonArray("artifacts", new JsonArray()).stream()
            .map(JsonObject.class::cast)
            .toList();
    private static final byte[] MANIFEST = readResourceBytes(MANIFEST_RESOURCE);
    private static final Set<String> SUPPORTED = Set.of(
            "tools-list",
            "tools-call-simple-text",
            "tools-call-error",
            "tools-call-image",
            "tools-call-audio",
            "tools-call-embedded-resource",
            "tools-call-mixed-content",
            "tools-call-with-progress",
            "server-stateless",
            "dns-rebinding-protection");
    private static final Set<String> DEFERRED = Set.of(
            "caching",
            "completion-complete",
            "server-sse-multiple-streams",
            "resources-list",
            "resources-read-text",
            "resources-read-binary",
            "resources-templates-read",
            "sep-2164-resource-not-found",
            "prompts-list",
            "prompts-get-simple",
            "prompts-get-with-args",
            "prompts-get-embedded-resource",
            "prompts-get-with-image",
            "input-required-result-basic-elicitation",
            "input-required-result-basic-sampling",
            "input-required-result-basic-list-roots",
            "input-required-result-request-state",
            "input-required-result-multiple-input-requests",
            "input-required-result-multi-round",
            "input-required-result-missing-input-response",
            "input-required-result-non-tool-request",
            "input-required-result-result-type",
            "input-required-result-unsupported-methods",
            "input-required-result-tampered-state",
            "input-required-result-capability-check",
            "input-required-result-ignore-extra-params",
            "input-required-result-validate-input");
    private static final Set<String> NOT_SCORED = Set.of(
            "tasks-lifecycle",
            "tasks-capability-negotiation",
            "tasks-wire-fields",
            "tasks-request-state-removal",
            "tasks-mrtr-input",
            "tasks-request-headers",
            "tasks-dispatch-and-envelope",
            "tasks-status-notifications",
            "tasks-required-task-error",
            "tasks-mrtr-composition",
            "json-schema-2020-12",
            "http-header-validation",
            "http-custom-header-server-validation");
    private static final Map<String, String> EXPECTED_INTEGRITY = Map.of(
            "conformance-runner",
            "sha512-0V/HZDdWHcg6j0zVBzBsXcPZ571IVi6umKgTpnBhtTx/jm/LONmGF6cIWL2k4Xjyps0OiHV6B37nj2s0pUg0nQ==",
            "typescript-client",
            "sha512-8f1OghQ2rjzIOfqgUCP+8GiUWqRs89njoWLNqAe8kWmDePv3s1fZXseej+QXemssEuuOvLLmLO/kqM3IQHtISw==",
            "go-sdk",
            "h1:yqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44=");
    private static final Map<String, String> EXPECTED_SOURCE_REFERENCES = Map.of(
            "conformance-runner",
            "https://registry.npmjs.org/@modelcontextprotocol/conformance/-/conformance-0.2.0-alpha.10.tgz",
            "typescript-client",
            "https://registry.npmjs.org/@modelcontextprotocol/client/-/client-2.0.0.tgz",
            "go-sdk",
            "https://github.com/modelcontextprotocol/go-sdk/releases/tag/v1.7.0");
    private static final Map<String, String> EXPECTED_INVOCATIONS = Map.of(
            "conformance-runner",
            "npx @modelcontextprotocol/conformance@0.2.0-alpha.10 server --url <url> --scenario <id> --spec-version 2026-07-28",
            "typescript-client",
            "node client.mjs --url <url> --scenario <scenario>",
            "go-sdk",
            "go run . --url <url> --scenario <scenario>");

    private static Stream<String> contractRows() {
        return Stream.of(ENTRY_ROW, VERSION_ROW, INTEGRITY_ROW, INVOCATION_ROW, DIGEST_ROW, PARTITION_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractRows")
    @DisplayName("external pin-lock contract matrix")
    void shouldEnforcePinLockContractMatrix(String row) {
        switch (row) {
            case ENTRY_ROW -> assertRequiredEntries();
            case VERSION_ROW -> assertExactVersions();
            case INTEGRITY_ROW -> assertIntegrityValues();
            case INVOCATION_ROW -> assertInvocationForm();
            case DIGEST_ROW -> assertManifestDigest();
            case PARTITION_ROW -> assertScenarioPartition();
            default -> fail("unknown pin-lock contract row: " + row);
        }
    }

    private static void assertRequiredEntries() {
        assertThat(PIN_LOCK.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(ARTIFACTS)
                .extracting(artifact -> artifact.getString("id"))
                .containsExactlyInAnyOrder("conformance-runner", "typescript-client", "go-sdk");
        Map<String, String> actualSourceReferences = new LinkedHashMap<>();
        ARTIFACTS.forEach(artifact ->
                actualSourceReferences.put(artifact.getString("id"), artifact.getString("sourceReference")));
        assertThat(actualSourceReferences).containsExactlyInAnyOrderEntriesOf(EXPECTED_SOURCE_REFERENCES);
        assertThat(actualSourceReferences.values())
                .allSatisfy(reference ->
                        assertThat(URI.create(reference).getScheme()).isEqualTo("https"));
    }

    private static void assertExactVersions() {
        boolean runnerExceptionScoped = artifact("conformance-runner")
                .getString("prereleaseException", "")
                .contains("requirements-2026-07-28.yaml");
        assertThat(List.of(exactVersionViolationCount(), runnerExceptionScoped)).containsExactly(0L, true);
        assertThat(artifact("conformance-runner").getString("version")).isEqualTo("0.2.0-alpha.10");
        assertThat(artifact("typescript-client").getString("version")).isEqualTo("2.0.0");
        assertThat(artifact("go-sdk").getString("version")).isEqualTo("v1.7.0");
    }

    private static void assertIntegrityValues() {
        Map<String, String> actual = new LinkedHashMap<>();
        ARTIFACTS.forEach(artifact -> actual.put(artifact.getString("id"), artifact.getString("integrity")));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(EXPECTED_INTEGRITY);
    }

    private static void assertInvocationForm() {
        assertThat(PIN_LOCK.getString("wireRevision")).isEqualTo("2026-07-28");
        assertThat(PIN_LOCK.getString("invocationForm"))
                .isEqualTo("server --url <url> --scenario <id> --spec-version 2026-07-28");
        assertThat(PIN_LOCK.encode()).doesNotContain("--requirements", "--expected-failures");
        Map<String, String> actualInvocations = new LinkedHashMap<>();
        ARTIFACTS.forEach(
                artifact -> actualInvocations.put(artifact.getString("id"), artifact.getString("invocation")));
        assertThat(actualInvocations).containsExactlyInAnyOrderEntriesOf(EXPECTED_INVOCATIONS);
    }

    private static void assertManifestDigest() {
        JsonObject manifest = PIN_LOCK.getJsonObject("manifest", new JsonObject());
        assertThat(manifest.getString("resource")).isEqualTo(MANIFEST_RESOURCE);
        assertThat(manifest.getString("sha256")).isEqualTo(MANIFEST_SHA256);
        assertThat(sha256(MANIFEST)).isEqualTo(MANIFEST_SHA256);
    }

    private static void assertScenarioPartition() {
        assertManifestDigest();
        JsonArray supportedValues = PIN_LOCK.getJsonArray("supportedScenarios", new JsonArray());
        JsonArray deferredValues = PIN_LOCK.getJsonArray("deferredScenarios", new JsonArray());
        JsonArray notScoredValues = PIN_LOCK.getJsonArray("notScoredUpstream", new JsonArray());
        assertThat(List.of(supportedValues.size(), deferredValues.size(), notScoredValues.size()))
                .containsExactly(10, 27, 13);
        Set<String> recordedSupported = stringSet(supportedValues);
        Set<String> recordedDeferred = stringSet(deferredValues);
        Set<String> recordedNotScored = stringSet(notScoredValues);

        assertThat(recordedSupported).containsExactlyInAnyOrderElementsOf(SUPPORTED);
        assertThat(recordedDeferred).containsExactlyInAnyOrderElementsOf(DEFERRED);
        assertThat(recordedNotScored).containsExactlyInAnyOrderElementsOf(NOT_SCORED);
        assertThat(recordedSupported).doesNotContainAnyElementsOf(recordedDeferred);

        ManifestIds manifestIds = parseManifest(MANIFEST);
        Set<String> scored = new HashSet<>(recordedSupported);
        scored.addAll(recordedDeferred);
        assertThat(scored).hasSize(37).containsExactlyInAnyOrderElementsOf(manifestIds.serverScored());
        assertThat(recordedNotScored).hasSize(13).containsExactlyInAnyOrderElementsOf(manifestIds.serverNotScored());
    }

    private static long exactVersionViolationCount() {
        return ARTIFACTS.stream()
                .filter(Predicate.not(McpExternalPinLockTest::hasAllowedExactVersion))
                .count();
    }

    private static boolean hasAllowedExactVersion(JsonObject artifact) {
        String version = artifact.getString("version", "");
        return switch (artifact.getString("id", "")) {
            case "conformance-runner" ->
                version.equals("0.2.0-alpha.10")
                        && artifact.getString("prereleaseException", "").contains("requirements-2026-07-28.yaml");
            case "typescript-client" -> version.matches("[0-9]+\\.[0-9]+\\.[0-9]+");
            case "go-sdk" -> version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+");
            default -> false;
        };
    }

    private static JsonObject artifact(String id) {
        return ARTIFACTS.stream()
                .filter(artifact -> id.equals(artifact.getString("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing external artifact: " + id));
    }

    private static Set<String> stringSet(JsonArray values) {
        return new LinkedHashSet<>(values.stream().map(String.class::cast).toList());
    }

    private static ManifestIds parseManifest(byte[] bytes) {
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        Set<String> server = new LinkedHashSet<>();
        Set<String> notScored = new LinkedHashSet<>();
        boolean inServer = false;
        boolean inNotScored = false;
        String candidate = null;
        for (String line : lines) {
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String readResource(String resource) {
        return new String(readResourceBytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String resource) {
        try (InputStream input = McpExternalPinLockTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError("could not read test resource: " + resource, failure);
        }
    }

    private record ManifestIds(Set<String> serverScored, Set<String> serverNotScored) {}
}
