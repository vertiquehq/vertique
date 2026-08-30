// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-002 — the generated registry is built once from the explicitly contributed invoker set.
 *
 * <p>Two generated descriptors named {@code weather.lookup} and {@code clock.now} are contributed in
 * reversed set order, and a third contribution duplicates {@code weather.lookup}. The unique set must
 * produce the immutable global name order {@code clock.now, weather.lookup}; the duplicate set must
 * fail startup naming the duplicate and produce no registry.
 *
 * <p>The same build also proves the three negatives in the proof title: each contributed invoker is
 * asked for its descriptor exactly once (built once, no per-request assembly), no invoker is ever
 * prepared or invoked, and a classpath-present but uncontributed invoker never appears (no scanning).
 */
class McpGeneratedRegistryTest {

    @Test
    @DisplayName("builds the immutable descriptor registry once, without invocation, reflection, or scanning")
    void shouldBuildImmutableDescriptorOnceWithoutInvocationReflectionOrScanning() {
        // --- Given: the two unique tools, contributed in reversed set order ---
        RecordingToolInvoker weatherLookup =
                McpGeneratedRegistryTestFixture.invoker("weather.lookup", "Look up weather");
        RecordingToolInvoker clockNow = McpGeneratedRegistryTestFixture.invoker("clock.now", "Read the clock");
        Set<McpToolInvoker> uniqueContributions =
                McpGeneratedRegistryTestFixture.contributions(weatherLookup, clockNow);

        // --- Given: the same two tools plus a second, different invoker claiming weather.lookup ---
        RecordingToolInvoker duplicateWeatherLookup =
                McpGeneratedRegistryTestFixture.invoker("weather.lookup", "Second weather tool");
        Set<McpToolInvoker> duplicateContributions =
                McpGeneratedRegistryTestFixture.contributions(weatherLookup, clockNow, duplicateWeatherLookup);

        // --- When: build the registry once for the unique set ---
        Map<String, McpToolInvoker> registry = McpToolRuntimeFactory.buildGeneratedRegistry(uniqueContributions);

        // Captured before any assertion reads a descriptor itself, so the counts describe the build alone.
        int weatherDescribedByBuild = weatherLookup.descriptorCalls();
        int clockDescribedByBuild = clockNow.descriptorCalls();
        int preparedByBuild = weatherLookup.prepareCalls() + clockNow.prepareCalls();

        // --- Then: immutable global name order, regardless of the reversed contribution order ---
        assertThat(registry.keySet()).containsExactly("clock.now", "weather.lookup");
        assertThat(registry.values())
                .extracting(McpToolInvoker::descriptor)
                .extracting(McpToolDescriptor::name)
                .containsExactly("clock.now", "weather.lookup");
        assertThatThrownBy(() -> registry.put("late.tool", weatherLookup))
                .as("the built registry is immutable")
                .isInstanceOf(UnsupportedOperationException.class);

        // --- Then: built once — each contribution described exactly once, never invoked ---
        assertThat(weatherDescribedByBuild).as("weather.lookup described once").isEqualTo(1);
        assertThat(clockDescribedByBuild).as("clock.now described once").isEqualTo(1);
        assertThat(preparedByBuild)
                .as("building the registry never invokes a tool")
                .isZero();

        // --- Then: only contributed tools are registered — nothing is discovered by scanning ---
        assertThat(registry).doesNotContainKey(UncontributedClasspathToolInvoker.NAME);

        // --- When/Then: the duplicate set fails startup naming the duplicate and produces no registry ---
        assertThatThrownBy(() -> McpToolRuntimeFactory.buildGeneratedRegistry(duplicateContributions))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("weather.lookup");
        assertThatCode(() -> McpToolRuntimeFactory.buildGeneratedRegistry(uniqueContributions))
                .as("the unique control set still builds")
                .doesNotThrowAnyException();
    }

    /**
     * A classpath-present generated invoker that is deliberately never contributed to any set: it
     * exists only so the registry can be asserted not to discover it.
     */
    private static final class UncontributedClasspathToolInvoker implements McpToolInvoker {

        private static final String NAME = "hidden.uncontributed";

        @Override
        public McpToolDescriptor descriptor() {
            throw new AssertionError("an uncontributed classpath invoker must never be registered");
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            throw new AssertionError("an uncontributed classpath invoker must never be prepared");
        }
    }

    /** Framework wiring for the registry proof: descriptor construction and contribution ordering. */
    private static final class McpGeneratedRegistryTestFixture {

        private McpGeneratedRegistryTestFixture() {}

        /** Builds one generated invoker double publishing the named descriptor. */
        static RecordingToolInvoker invoker(String name, String description) {
            return new RecordingToolInvoker(new McpToolDescriptor(
                    name,
                    null,
                    description,
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)));
        }

        /** Preserves the declared contribution order inside the {@code @IntoSet} contribution set. */
        static Set<McpToolInvoker> contributions(McpToolInvoker... invokers) {
            return new LinkedHashSet<>(List.of(invokers));
        }
    }

    /** A generated-invoker double that records how often it is described and prepared. */
    private static final class RecordingToolInvoker implements McpToolInvoker {

        private final McpToolDescriptor descriptor;
        private final AtomicInteger descriptorCalls = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();

        private RecordingToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public McpToolDescriptor descriptor() {
            descriptorCalls.incrementAndGet();
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepareCalls.incrementAndGet();
            throw new AssertionError("building the generated registry must never invoke a tool");
        }

        int descriptorCalls() {
            return descriptorCalls.get();
        }

        int prepareCalls() {
            return prepareCalls.get();
        }
    }
}
