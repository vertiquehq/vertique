// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.mcp.tool.McpPreparedToolCall;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Review-finding proof (round-14 remediation, defect #4): {@code McpToolInvokerEmitter} emitted
 * {@code Map.copyOf(normalizedArguments)} for the generated {@code prepare()}'s returned {@code
 * PreparedCall}. Two defects, both proved here through a real generated invoker compiled by the real
 * {@link dev.vertique.codegen.mcp.McpToolProcessor} (never a hand-written stand-in):
 *
 * <ul>
 *   <li>{@code Map.copyOf} throws {@link NullPointerException} on a null value, but INP-001
 *       deliberately preserves an explicit-null {@code Optional<T>} argument all the way through —
 *       so a valid explicit-null call must NPE only if the fix regresses.
 *   <li>{@code Map.copyOf} freezes only the root map; a nested {@code Map}/{@code List} value stays
 *       mutable, violating {@link McpPreparedToolCall#normalizedArguments()}'s deeply-immutable
 *       contract.
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedNullPreservingImmutableArgumentsTest {

    @Test
    void shouldPreserveExplicitNullAndRejectNestedMutation() throws Exception {
        McpGeneratedNullPreservingImmutableArgumentsTestFixture fixture =
                McpGeneratedNullPreservingImmutableArgumentsTestFixture.start();

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "Helsinki");
        address.put("zip", "00100");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", address);
        // Explicit wire null, not an absent key: INP-001 preserves this exactly, and stage 3
        // materializes it to Optional.empty() for the Optional<String> nickname parameter.
        arguments.put("nickname", null);

        // DECISIVE (half 1): pre-fix, building the PreparedCall via Map.copyOf(normalizedArguments)
        // threw NullPointerException here, even though every earlier stage (schema validation,
        // canonicalization/sanitization, materialization, Bean Validation) already succeeded — the
        // valid call never reached the application handler at all. Post-fix this must not throw.
        McpPreparedToolCall prepared = fixture.invoker()
                .prepare(arguments, new McpGeneratedNullPreservingImmutableArgumentsTestFixture.NeverCancelledSignal());

        Map<String, Object> normalizedArguments = prepared.normalizedArguments();
        assertThat(normalizedArguments)
                .as("DECISIVE: the explicit-null nickname must survive into the exposed argument tree, "
                        + "not be dropped or coerced away")
                .containsEntry("nickname", null);

        // DECISIVE (half 2): the nested "address" value must reject mutation too — Map.copyOf only
        // ever froze the root map, leaving this nested Map mutable.
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedAddress = (Map<String, Object>) normalizedArguments.get("address");
        assertThatThrownBy(() -> normalizedAddress.put("city", "Tampere"))
                .as("DECISIVE: a nested Map value in the prepared call's argument tree must be "
                        + "unmodifiable too, not just the root map")
                .isInstanceOf(UnsupportedOperationException.class);

        // The root map itself must still reject mutation, exactly as Map.copyOf already guaranteed.
        assertThatThrownBy(() -> normalizedArguments.put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
