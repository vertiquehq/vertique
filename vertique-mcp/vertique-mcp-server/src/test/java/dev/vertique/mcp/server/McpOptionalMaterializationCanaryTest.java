// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.tool.McpToolInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R03 TP-002 — {@code shouldProveOptionalMaterializationThroughGeneratedCode} (finding #428), plus
 * R13 item 3 (issue #440)'s zero-config mounting proof.
 *
 * <p>Drives the real generated {@code OptionalProbe} canary — emitted by {@link
 * dev.vertique.codegen.mcp.McpToolInvokerEmitter} only for a tool with an {@code Optional<T>}
 * parameter, and run once, from the generated invoker's constructor, through {@code McpToolRuntime
 * #verifyOptionalMaterialization} — with omitted, explicit-null, and present cases (contract §4.1).
 *
 * <p><strong>Every case is real, not synthetic.</strong> {@code shouldMountForTheZeroConfigDefaultProfile}
 * exercises the framework's actual zero-config default — before R13 item 3 (issue #440), that default
 * was the reserved {@code vertx} profile, and this exact canary failed startup for it (confirmed
 * directly by this same row prior to the fix: see {@code McpToolRuntime}'s javadoc and the mutation
 * below for why {@code vertx}'s {@code DatabindCodec} registers no {@code Jdk8Module}). The fallback is
 * now {@code vertique}, which does register it, so this row now mounts.
 * {@code shouldFailStartupForAnExplicitlyIncapableProfile} proves the canary's own contract is
 * unchanged: a profile that is genuinely {@code Optional}-incapable — reached through an explicit MCP
 * boundary default, not the zero-config tail — still fails startup exactly as before.
 * {@code shouldMountForACapableProfile} exercises a profile that does register {@code Jdk8Module}.
 */
class McpOptionalMaterializationCanaryTest {

    @Test
    @DisplayName("shouldMountForTheZeroConfigDefaultProfile")
    void shouldMountForTheZeroConfigDefaultProfile() throws Exception {
        // Given: a real generated invoker for an Optional-reaching tool, and the framework's actual
        // zero-config default profile — vertique (issue #440), not the reserved vertx profile.
        ProcessorTestHarness.Result compiled = McpOptionalMaterializationCanaryTestFixture.compile();
        McpToolRuntimeFactory zeroConfig = McpOptionalMaterializationCanaryTestFixture.zeroConfigProfileFactory();

        // When: construction runs the same canary against the real zero-config default.
        McpToolInvoker invoker = McpOptionalMaterializationCanaryTestFixture.construct(compiled, zeroConfig);

        // Then (DECISIVE, R13 item 3 / issue #440): the tool mounts. Before the fix, this exact call
        // threw ConfigurationException naming "probe.register" — the zero-config vertx profile could
        // not materialize Optional<T>. "The tool never mounts" was exactly "construction throws" (see
        // shouldFailStartupForAnExplicitlyIncapableProfile below for that failure shape, now reached
        // only by an explicitly incapable profile).
        assertNotNull(invoker, "R13 item 3 (issue #440): the zero-config default must mount an Optional-reaching tool");
    }

    @Test
    @DisplayName("shouldFailStartupForAnExplicitlyIncapableProfile")
    void shouldFailStartupForAnExplicitlyIncapableProfile() throws Exception {
        // Given: a real generated invoker for an Optional-reaching tool, and a profile explicitly
        // selected to be Optional-incapable — proving the canary's own contract is unaffected by which
        // profile the zero-config tail itself resolves to.
        ProcessorTestHarness.Result compiled = McpOptionalMaterializationCanaryTestFixture.compile();
        McpToolRuntimeFactory incapable =
                McpOptionalMaterializationCanaryTestFixture.explicitlyIncapableProfileFactory();

        // When / Then (DECISIVE): construction — the generated invoker's constructor, which runs the
        // canary before returning — must fail with ConfigurationException naming the tool, rather than
        // silently producing an invoker whose Optional arguments would materialize wrong at request
        // time. "The tool never mounts" is exactly "construction throws" here: nothing later in
        // composition can obtain an McpToolInvoker from a constructor that never returned one.
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> McpOptionalMaterializationCanaryTestFixture.construct(compiled, incapable),
                "an Optional-unmaterializable profile must fail startup, not be inferred from module ids");
        assertTrue(
                failure.getMessage().contains("probe.register"),
                "the failure must name the affected tool: " + failure.getMessage());
    }

    @Test
    @DisplayName("shouldMountForACapableProfile")
    void shouldMountForACapableProfile() throws Exception {
        // Given: the same real generated invoker, bound instead to a profile that does materialize
        // Optional<T> correctly.
        ProcessorTestHarness.Result compiled = McpOptionalMaterializationCanaryTestFixture.compile();
        McpToolRuntimeFactory materializable =
                McpOptionalMaterializationCanaryTestFixture.materializableProfileFactory();

        // When: construction runs the same canary against the capable profile.
        McpToolInvoker invoker = McpOptionalMaterializationCanaryTestFixture.construct(compiled, materializable);

        // Then: the tool mounts — the canary's omitted/explicit-null/present cases all resolved the
        // way an Optional<T> parameter's contract requires, so composition proceeds normally.
        assertNotNull(invoker, "a materialization-capable profile must mount the tool");
    }
}
