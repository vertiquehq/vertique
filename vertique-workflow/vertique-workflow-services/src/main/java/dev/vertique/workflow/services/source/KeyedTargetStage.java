// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.workflow.source.SignalNoMatch;
import java.util.function.Function;

/**
 * What follows a signal's key source: the signal's payload mapping and its own no-match policy, then
 * the next signal, any shared stage, or {@code build()}.
 *
 * <p>It offers no key source, so a signal cannot name a second one.
 *
 * @param <T> the type of the decoded message body
 * @param <SELF> the type of the builder
 * @param <R> what {@code build()} returns on the transport
 */
public interface KeyedTargetStage<T, SELF, R> extends SignalStages<T, SELF, R> {

    /**
     * Builds the signal's payload from the decoded message. It is required unless the message type
     * equals the signal's parameter type.
     *
     * @param payload answers the payload from the message
     * @return this stage
     */
    KeyedTargetStage<T, SELF, R> payload(Function<T, ?> payload);

    /**
     * Sets what a message that matches no instance does for this signal alone, over the source's
     * default.
     *
     * @param policy the policy of this signal
     * @return this stage
     */
    KeyedTargetStage<T, SELF, R> onNoMatch(SignalNoMatch policy);
}
