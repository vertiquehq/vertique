// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T018 TP-002 — the least-privilege boundary of opt-in bounded tool-value observation.
 *
 * <p>Builds one {@link McpToolInputObservation} from a materialized carrier whose source scope also
 * holds raw headers, a raw credential, and the original raw body {@code Buffer}, delivers it through
 * the real {@link McpCompletionCoordinator#publishToolInput} to a recording session that
 * deliberately retains its reference, and then inspects — by reflection, not by trusting the
 * implementation — whether the observation exposes a path back to any raw source, and whether the
 * coordinator itself still holds a reference to the observation or its value tree once the callback
 * has returned.
 *
 * <p>Per the frozen contract (§4.4) and this task's TP-002, this is the whole of the enforceable
 * claim: nothing here asserts that the recording session's own deliberately retained reference
 * becomes unusable or invalid — an immutable record cannot revoke itself, so callback-scoped use
 * remains a documented obligation on implementors, not a framework-enforced one.
 */
@DisplayName("MCP opt-in value observation — T018 least-privilege boundary")
class McpValueObservationLeastPrivilegeTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("exposes only the bounded normalized value tree and retains nothing framework-side")
    void shouldExposeNoRawTransportDataAndRetainNothingFrameworkSide() {
        // Given: a materialized carrier whose source scope also holds raw headers, a raw credential,
        // and the original raw body buffer, and an input observation built from only its normalized
        // argument tree.
        McpValueObservationLeastPrivilegeTestFixture.SourceCarrier carrier =
                McpValueObservationLeastPrivilegeTestFixture.rawSourceCarrier();
        McpToolInvocationContext toolContext = McpValueObservationLeastPrivilegeTestFixture.toolContext();
        McpToolInputObservation observation = new McpToolInputObservation(toolContext, carrier.normalizedArguments());

        // Given: a recording observer that deliberately retains the reference it is handed, and a
        // dispatcher (the real McpCompletionCoordinator) instrumented, via reflection, to expose every
        // framework-owned reference still reachable after the callback returns.
        McpValueObservationLeastPrivilegeTestFixture.RecordingValueObservation recording =
                new McpValueObservationLeastPrivilegeTestFixture.RecordingValueObservation();
        Context context = vertx.getOrCreateContext();
        McpCompletionCoordinator coordinator =
                McpValueObservationLeastPrivilegeTestFixture.coordinatorFor(context, recording);

        // When: deliver exactly one onToolInput callback and let the observer retain its reference.
        coordinator.publishToolInput(observation);

        // Then (DECISIVE — counter provably increments): the capable session received exactly one call.
        assertThat(recording.callCount())
                .as("the value-callback counter must provably increment on a genuine delivery")
                .isEqualTo(1);
        assertThat(recording.retained())
                .as("the observer's own deliberate retention is allowed and is the exact delivered instance")
                .isSameAs(observation);

        // Then (DECISIVE — least privilege): the observation exposes no reachable path back to the raw
        // headers, raw credential, or raw body buffer the same source scope also held.
        assertThat(McpValueObservationLeastPrivilegeTestFixture.excludesRawSources(observation, carrier))
                .as("raw body bytes, headers, and credentials must be unreachable through the observation")
                .isTrue();

        // Then (DECISIVE — framework non-retention): once the callback above has returned, no
        // framework-owned field on the coordinator itself holds a reference to the observation or its
        // value tree — observed by reflecting over the coordinator's actual state, not asserted by
        // inspection of the source.
        int frameworkReferences = McpValueObservationLeastPrivilegeTestFixture.countFrameworkReferences(
                coordinator, observation, observation.normalizedArguments());
        assertThat(frameworkReferences)
                .as("no framework-owned object may hold a reference to the observation or its value tree "
                        + "once the callback has returned — this is the whole of the enforceable claim; "
                        + "nothing here asserts that the observer's own retained reference above becomes "
                        + "unusable")
                .isZero();
    }
}
