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
 * {@code shouldProveOptionalMaterializationThroughGeneratedCode}, plus the zero-config mounting
 * proof.
 *
 * <p>Drives the real generated {@code OptionalProbe} canary — emitted by {@link
 * dev.vertique.codegen.mcp.McpToolInvokerEmitter} only for a tool with an {@code Optional<T>}
 * parameter, and run once, from the generated invoker's constructor, through {@code McpToolRuntime
 * #verifyOptionalMaterialization} — with omitted, explicit-null, and present cases.
 *
 * <p><strong>Every case is real, not synthetic.</strong> {@code shouldMountForTheZeroConfigDefaultProfile}
 * exercises the framework's actual zero-config default — the {@code vertique} profile, which
 * registers {@code Jdk8Module}, so this row mounts. The reserved {@code vertx} profile's {@code
 * DatabindCodec} registers no {@code Jdk8Module} (see {@code McpToolRuntime}'s javadoc) and would fail
 * this canary at startup.
 * {@code shouldFailStartupForAnExplicitlyIncapableProfile} proves the canary's own contract:
 * a profile that is genuinely {@code Optional}-incapable — reached through an explicit MCP
 * boundary default, not the zero-config tail — still fails startup.
 * {@code shouldMountForACapableProfile} exercises a profile that does register {@code Jdk8Module}.
 */
class McpOptionalMaterializationCanaryTest {

    @Test
    @DisplayName("shouldMountForTheZeroConfigDefaultProfile")
    void shouldMountForTheZeroConfigDefaultProfile() throws Exception {
        // Given: a real generated invoker for an Optional-reaching tool, and the framework's actual
        // zero-config default profile — vertique, not the reserved vertx profile.
        ProcessorTestHarness.Result compiled = McpOptionalMaterializationCanaryTestFixture.compile();
        McpToolRuntimeFactory zeroConfig = McpOptionalMaterializationCanaryTestFixture.zeroConfigProfileFactory();

        // When: construction runs the same canary against the real zero-config default.
        McpToolInvoker invoker = McpOptionalMaterializationCanaryTestFixture.construct(compiled, zeroConfig);

        // Then (DECISIVE): the tool mounts. A profile that could not materialize Optional<T> would
        // make this exact call throw ConfigurationException naming "probe.register". "The tool never
        // mounts" is exactly "construction throws" (see
        // shouldFailStartupForAnExplicitlyIncapableProfile below for that failure shape, reached
        // only by an explicitly incapable profile).
        assertNotNull(invoker, "the zero-config default must mount an Optional-reaching tool");
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
