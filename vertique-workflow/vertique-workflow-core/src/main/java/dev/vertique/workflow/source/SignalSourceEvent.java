// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * The outcome of one signal of a message received by a correlated signal source, or of the message itself when it
 * ended before a signal was chosen.
 *
 * <p>The event never carries a key, a header or a payload.
 *
 * @param source the name of the source that received the message
 * @param transport the transport that carried the message, such as {@code kafka} or {@code camel}
 * @param contractName the name of the workflow contract the signal addressed, or {@code null} when the
 *     outcome was reached before a signal was chosen
 * @param signalName the name of the signal, or {@code null} when the outcome was reached before a signal
 *     was chosen
 * @param definitionId the definition the signal was recorded for, or {@code null} when none applies
 * @param outcome what happened to the message
 */
public record SignalSourceEvent(
        String source,
        String transport,
        @Nullable String contractName,
        @Nullable String signalName,
        @Nullable String definitionId,
        Outcome outcome) {

    /**
     * Validates the components that are required.
     *
     * @throws NullPointerException if {@code source}, {@code transport} or {@code outcome} is {@code null}
     */
    public SignalSourceEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** What happened to a message. */
    public enum Outcome {
        /** The signal was recorded; the executor applies it afterwards. */
        RECORDED,
        /** No instance matched the key. */
        NO_MATCH,
        /** More than one instance matched the key. */
        AMBIGUOUS,
        /** The signal was already recorded. */
        DEDUPLICATED,
        /** The message was dropped without being recorded. */
        IGNORED,
        /** The message could not be decoded. */
        UNDECODABLE,
        /** The message named no scope. */
        SCOPE_EMPTY,
        /** The message was refused because no identity could act for it. */
        REFUSED_IDENTITY,
        /** The message was refused as invalid input. */
        INVALID_INPUT,
        /** The message was refused because a limit was reached. */
        LIMITED,
        /** The message was refused because it exceeded a size bound. */
        OVERSIZED,
        /** A stage of the source failed. */
        STAGE_FAILED
    }
}
