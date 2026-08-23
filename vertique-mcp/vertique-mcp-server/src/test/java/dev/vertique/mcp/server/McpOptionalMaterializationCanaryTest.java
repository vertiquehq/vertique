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
 * R03 TP-002 — {@code shouldProveOptionalMaterializationThroughGeneratedCode} (finding #428).
 *
 * <p>Drives the real generated {@code OptionalProbe} canary — emitted by {@link
 * dev.vertique.codegen.mcp.McpToolInvokerEmitter} only for a tool with an {@code Optional<T>}
 * parameter, and run once, from the generated invoker's constructor, through {@code McpToolRuntime
 * #verifyOptionalMaterialization} — with omitted, explicit-null, and present cases (contract §4.1).
 *
 * <p><strong>Both cases are real, not synthetic.</strong> {@code shouldFailStartup...} exercises the
 * framework's own zero-config {@code vertx} profile: Vert.x's {@code DatabindCodec} registers no
 * {@code Jdk8Module}, confirmed directly (see {@code McpToolRuntime}'s javadoc and the mutation below)
 * — an application that declares an {@code Optional<T>} MCP tool parameter and configures no JSON
 * profile gets exactly this profile today. {@code shouldMountForACapableProfile} exercises a profile
 * that does register {@code Jdk8Module}.
 */
class McpOptionalMaterializationCanaryTest {

    @Test
    @DisplayName("shouldFailStartupForAnOptionalUnmaterializableProfile")
    void shouldFailStartupForAnOptionalUnmaterializableProfile() throws Exception {
        // Given: a real generated invoker for an Optional-reaching tool, and the framework's own
        // zero-config vertx profile — which cannot materialize Optional<T> correctly.
        ProcessorTestHarness.Result compiled = McpOptionalMaterializationCanaryTestFixture.compile();
        McpToolRuntimeFactory unmaterializable =
                McpOptionalMaterializationCanaryTestFixture.unmaterializableProfileFactory();

        // When / Then (DECISIVE): construction — the generated invoker's constructor, which runs the
        // canary before returning — must fail with ConfigurationException naming the tool, rather than
        // silently producing an invoker whose Optional arguments would materialize wrong at request
        // time. "The tool never mounts" is exactly "construction throws" here: nothing later in
        // composition can obtain an McpToolInvoker from a constructor that never returned one.
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> McpOptionalMaterializationCanaryTestFixture.construct(compiled, unmaterializable),
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
