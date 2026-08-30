// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repair R06 (issue #430): {@code vertique-micrometer-core}'s {@link CardinalityGuard#GUARDED_TAG_KEYS}
 * froze {@code method}, {@code outcome}, and {@code error.type} — carried over from earlier adapter
 * phases — but never gained {@link McpServerMetricsObserver}'s own {@code tool}, {@code result.type},
 * or {@code transport.outcome}. Today's only defence for those three is structural bounding at the
 * source ({@link McpRequestTerminalEvent}'s compact constructor); a future producer that bypasses that
 * bound would reach the registry with no cardinality guard at all.
 *
 * <p><b>Derived, not hand-listed — both sides.</b> "Derived" is the whole point: a test that hardcodes
 * the three new key names next to the production list proves only that someone typed the same three
 * strings twice, and stays green if a fourth dimension is added and left unguarded.
 *
 * <ul>
 *   <li><b>Emitted side</b> — drives the real {@link McpServerMetricsObserver} against a bare, unfiltered
 *       {@link SimpleMeterRegistry} and reads back every {@link Tag#getKey()} that actually landed on a
 *       registered meter. This is the exact derivation {@link McpServerMetricsObserverTest}'s own
 *       {@code shouldNeverTagPayloadIdentityOrTraceIds} row already uses for a different purpose
 *       (proving no identity/trace tag is ever emitted); here it drives the affirmative check instead.
 *   <li><b>Guarded side</b> — reads {@link CardinalityGuard#GUARDED_TAG_KEYS} via reflection, not a
 *       second copy of its contents. {@code vertique-micrometer-core} does not, and must not, depend on
 *       {@code vertique-micrometer-mcp} — the dependency runs the other way — so this module has no
 *       import path to that package-private field. Reflection reaches the real production field
 *       directly, the same technique this repository's own per-module public-surface guards
 *       ({@code McpCoreInventoryChecker} and siblings) already use to inspect another type's declared
 *       members without an import.
 * </ul>
 *
 * <p>A fourth dimension added to {@link McpServerMetricsObserver} without a matching {@link
 * CardinalityGuard#GUARDED_TAG_KEYS} entry fails this test the moment it is emitted by the fixture
 * below — it does not need to be anticipated by name in this file.
 */
class McpMetricsCardinalityGuardContractTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);
    private static final Instant COMPLETED_AT = TERMINAL_AT.plusMillis(1);

    @Test
    @DisplayName("shouldGuardEveryEmittedMcpDimension")
    void shouldGuardEveryEmittedMcpDimension() throws ReflectiveOperationException {
        // Given: the real observer driven with one tools/call + INPUT_VALIDATION completion — the one
        // event shape that records all three of the observer's meters (request timer,
        // validation-failures counter, tool-call timer) from a single completed event, so every tag
        // key the adapter can ever emit is exercised by this one fixture.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpServerMetricsObserver observer = new McpServerMetricsObserver(registry, Optional.empty());
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.toolError(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                "guard-contract-tool",
                McpErrorType.INPUT_VALIDATION,
                200,
                null,
                null,
                null,
                null);
        McpRequestObservation session = observer.open(STARTED_AT);
        session.onCompleted(McpRequestCompletedEvent.written(terminal, COMPLETED_AT));

        Set<String> emittedKeys = new TreeSet<>();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                emittedKeys.add(tag.getKey());
            }
        }

        // Sanity: the fixture must actually exercise every frozen MCP tag key, including the three
        // issue #430 names — a derivation that trivially finds nothing would prove nothing.
        assertThat(emittedKeys)
                .as("fixture must exercise every frozen MCP tag key so the derivation is non-vacuous")
                .containsExactlyInAnyOrder(
                        McpServerMetricsObserver.TAG_METHOD,
                        McpServerMetricsObserver.TAG_TOOL,
                        McpServerMetricsObserver.TAG_OUTCOME,
                        McpServerMetricsObserver.TAG_ERROR_TYPE,
                        McpServerMetricsObserver.TAG_RESULT_TYPE,
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME);

        // When: read CardinalityGuard's frozen list from its own field — the production source of
        // truth — not a second hand-typed copy of its contents.
        List<String> guardedTagKeys = readGuardedTagKeys();

        // Then (DECISIVE): every key the adapter actually emitted must be in the guarded union. This
        // fails the moment an emitted key is unguarded, whether that gap is one of today's three or a
        // dimension added later and left off this list.
        assertThat(guardedTagKeys)
                .as("DECISIVE: CardinalityGuard.GUARDED_TAG_KEYS must cover every key the MCP adapter emits")
                .containsAll(emittedKeys);
    }

    /**
     * Reads {@code dev.vertique.micrometer.CardinalityGuard.GUARDED_TAG_KEYS} via reflection.
     *
     * <p>{@code vertique-micrometer-core} has no dependency on {@code vertique-micrometer-mcp} (the
     * compile dependency runs the other way), so this module cannot import the package-private field.
     * Reflection reaches the real field on the classpath instead of maintaining a second, hand-typed
     * copy of its contents that could silently drift the moment either list changed alone — which is
     * exactly how issue #430 happened.
     *
     * @return the frozen guarded-tag-key list, read live from {@link CardinalityGuard}
     * @throws ReflectiveOperationException if the field cannot be located or read — a signal the
     *     production class was renamed or restructured, not a result this test should mask
     */
    @SuppressWarnings("unchecked")
    private static List<String> readGuardedTagKeys() throws ReflectiveOperationException {
        Class<?> guardClass = Class.forName("dev.vertique.micrometer.CardinalityGuard");
        Field field = guardClass.getDeclaredField("GUARDED_TAG_KEYS");
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }
}
