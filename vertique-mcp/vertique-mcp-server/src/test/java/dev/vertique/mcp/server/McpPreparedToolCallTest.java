// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.mcp.tool.McpPreparedToolCall;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T014 TP-002 — {@link McpPreparedToolCall} exposes exactly its two frozen members;
 * {@code normalizedArguments()} is an unmodifiable, normalized (not raw-wire) defensive copy; and
 * cancelling the fixture-held signal makes {@code invoke()} settle as cancelled.
 *
 * <p>No assertion below reads the descriptor, cancellation signal, effective mapper, or materialized
 * carrier back off the prepared call — only off {@link McpPreparedToolCallTestFixture}, at the exact
 * point it passed them to {@code prepare(...)} — because writing such an assertion would require
 * adding the very accessor the frozen contract forbids.
 */
class McpPreparedToolCallTest {

    @Test
    @DisplayName("exposes exactly normalizedArguments() and invoke(), an unmodifiable normalized map, "
            + "and settles invoke() as cancelled when the fixture-held signal cancels")
    void shouldExposeOnlyTheTwoFrozenPreparedCallMembers() {
        // Given: a prepared call produced by a generated invoker's prepare(...) from one descriptor, a
        // cancellation signal, the effective mapper, and a materialized carrier — all four held by the
        // fixture that passed them in.
        McpPreparedToolCallTestFixture fixture = McpPreparedToolCallTestFixture.prepare();
        McpPreparedToolCall preparedCall = fixture.preparedCall();

        // When/Then: reflection over the prepared call's public members finds exactly two — no
        // descriptor, cancellation-signal, mapper, or carrier accessor exists to read.
        Class<?> preparedCallClass = preparedCall.getClass();
        Method[] publicMethodsDeclaredOutsideObject = Arrays.stream(preparedCallClass.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .toArray(Method[]::new);
        Field[] publicFields = preparedCallClass.getFields();

        assertThat(publicMethodsDeclaredOutsideObject.length + publicFields.length)
                .as("DECISIVE: the prepared call must expose exactly two public members")
                .isEqualTo(2);
        assertThat(publicMethodsDeclaredOutsideObject)
                .extracting(Method::getName)
                .as("the two public members must be exactly normalizedArguments() and invoke()")
                .containsExactlyInAnyOrder("normalizedArguments", "invoke");

        // When: the returned map is read and a mutation is attempted.
        Map<String, Object> normalizedArguments = preparedCall.normalizedArguments();

        // Then: it holds normalized values, not the raw wire argument the fixture passed to prepare(...).
        assertThat(normalizedArguments)
                .as("normalizedArguments() must hold the normalized value, not the raw wire argument")
                .containsExactly(Map.entry(
                        McpPreparedToolCallTestFixture.ARGUMENT_KEY, McpPreparedToolCallTestFixture.NORMALIZED_VALUE));
        assertThat(normalizedArguments.containsValue(McpPreparedToolCallTestFixture.RAW_VALUE))
                .as("the raw wire argument the fixture passed to prepare(...) must not survive unprocessed")
                .isFalse();

        // Then (DECISIVE): the returned map is an unmodifiable defensive copy that rejects mutation.
        assertThatThrownBy(() -> normalizedArguments.put("extra", "value"))
                .as("DECISIVE: normalizedArguments() must be an unmodifiable defensive copy")
                .isInstanceOf(UnsupportedOperationException.class);

        // When: invoke() is called, then the fixture-held cancellation signal is cancelled.
        var invocation = preparedCall.invoke();
        assertThat(invocation.isComplete())
                .as("invoke() must not have already settled, or the cancellation below would be vacuous")
                .isFalse();

        fixture.cancellation().cancel();

        // Then (DECISIVE): cancelling the fixture-held signal — never read back off the prepared call —
        // makes invoke() settle as cancelled, proving the signal reached the closure prepare(...) built.
        assertThat(invocation.failed())
                .as("DECISIVE: cancelling the fixture-held signal must make invoke() settle as cancelled")
                .isTrue();
        assertThat(invocation.cause()).isInstanceOf(CancellationException.class);
    }
}
