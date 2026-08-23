// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T021 composition proof (review remediation finding #6): {@link McpMicrometerModule} appeared, pre
 * this proof, in exactly one test as a reflection anchor — no test ever built a Dagger component
 * containing it, so its {@link McpRequestLifecycleObserver} contribution and its {@code
 * @BindsOptionalOf MetricsConfig} declaration were unexercised, including against co-installation
 * with {@code dev.vertique.micrometer.MicrometerModule}, which also declares {@code
 * @BindsOptionalOf MetricsConfig} — the exact composition an application performs. Mirrors {@code
 * McpTraceCompositionTest}'s omitting-vs-installed structure. Framework wiring lives behind {@link
 * McpMetricsCompositionTestFixture}; only the Given values, the one action per graph, and the
 * decisive assertions live here.
 */
class McpMetricsCompositionTest {

    private CompositeMeterRegistry composite;
    private SimpleMeterRegistry probe;

    @AfterEach
    void tearDown() {
        if (composite != null && probe != null) {
            composite.remove(probe);
        }
        if (probe != null) {
            probe.close();
        }
        composite = null;
        probe = null;
    }

    @Test
    @DisplayName("the omitting graph contributes zero observers; the installed graph, alongside "
            + "MicrometerModule, contributes exactly one observer that records one sample")
    void shouldContributeZeroObserversWhenOmittedAndOneCapableObserverWhenInstalled() {
        // Given/When: the omitting graph — McpMicrometerModule absent from its module list.
        McpMetricsCompositionTestFixture.OmittingComponent omitting =
                McpMetricsCompositionTestFixture.buildOmittingGraph();

        // Then (DECISIVE): zero observers — structurally, this graph never references
        // McpMicrometerModule or McpServerMetricsObserver.
        assertThat(omitting.lifecycleObservers())
                .as("DECISIVE: the omitting graph contributes zero lifecycle observers")
                .isEmpty();

        // Given/When: the installed graph — McpMicrometerModule composed alongside the real
        // MicrometerModule, which also declares @BindsOptionalOf MetricsConfig.
        McpMetricsCompositionTestFixture.InstalledComponent installed =
                McpMetricsCompositionTestFixture.buildInstalledGraph();

        // Then (DECISIVE): exactly one observer, the real McpServerMetricsObserver — proving the
        // multibinding contribution resolves and the two co-installed @BindsOptionalOf declarations
        // do not conflict.
        Set<McpRequestLifecycleObserver> observers = installed.lifecycleObservers();
        assertThat(observers)
                .as("DECISIVE: the installed graph contributes exactly one observer")
                .hasSize(1);
        McpRequestLifecycleObserver observer = observers.iterator().next();
        assertThat(observer)
                .as("the contributed observer is the real McpServerMetricsObserver, not a stand-in")
                .isInstanceOf(McpServerMetricsObserver.class);

        // Given: a probe registry attached to the injected (real, globally-held) composite so a
        // recorded sample is observable — the composite itself has no children until one is attached.
        MeterRegistry registry = installed.meterRegistry();
        assertThat(registry).isInstanceOf(CompositeMeterRegistry.class);
        composite = (CompositeMeterRegistry) registry;
        probe = new SimpleMeterRegistry();
        composite.add(probe);

        // When: drive one completed request through the resolved observer, exactly as the real
        // dispatcher would.
        McpRequestObservation session = observer.open(Instant.now());
        session.onCompleted(fixtureCompletedEvent());

        // Then (DECISIVE — sensitivity target): the request timer recorded exactly one sample.
        Timer timer = probe.find(McpServerMetricsObserver.REQUESTS_TIMER).timer();
        assertThat(timer).as("the requests timer must be registered").isNotNull();
        assertThat(timer.count())
                .as("DECISIVE: the installed graph's real observer must record exactly one sample")
                .isEqualTo(1);
    }

    private static McpRequestCompletedEvent fixtureCompletedEvent() {
        Instant startedAt = Instant.parse("2026-06-05T10:00:00Z");
        Instant terminalAt = startedAt.plusMillis(5);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.success(
                startedAt, terminalAt, McpMethod.TOOLS_CALL, "metrics.composition.fixture.tool", 200, null, null, null);
        return McpRequestCompletedEvent.written(terminal, terminalAt.plusMillis(1));
    }
}
