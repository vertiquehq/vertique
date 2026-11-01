// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.source.SignalMessage;
import io.vertx.core.Future;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The stages every correlated signal source shares, whatever its transport, so that a stage has one
 * name and one meaning.
 *
 * <p>A stage written after the first {@code signal} applies to the whole source. A function that
 * takes the decoded message ({@code when}, {@code dedupBy}) reads {@code T}; a function that takes a
 * {@link SignalMessage} ({@code scope}, {@code dispatchAs}) also reads headers and, on Kafka, the
 * record key. Every function runs on the event loop and must not block.
 *
 * @param <T> the type of the decoded message body
 * @param <SELF> the type of the builder, returned by each stage so calls chain
 * @param <R> what {@link #build()} returns on the transport
 */
public interface SignalStages<T, SELF, R> {

    /**
     * Excludes a message the predicate rejects: it concerns no workflow and is finished.
     *
     * @param predicate whether the message is considered
     * @return this builder
     */
    SELF when(Predicate<T> predicate);

    /**
     * Narrows every signal of a message to one scope, taken from the message. An empty answer means
     * the message concerns no workflow and never widens the match. The function must answer the same
     * for the same message.
     *
     * @param scope answers the scope key of the message
     * @return this builder
     */
    SELF scope(Function<SignalMessage<T>, Optional<String>> scope);

    /**
     * States outright that the source matches in every scope, which is also the default. A source
     * naming both {@code scope} and {@code anyScope} fails when it is built.
     *
     * @return this builder
     */
    SELF anyScope();

    /**
     * Sets the dedup key of a message. A null or blank answer is refused as invalid input.
     *
     * @param dedupKey answers the dedup key of the message
     * @return this builder
     */
    SELF dedupBy(Function<T, String> dedupKey);

    /**
     * Bounds each lookup and the {@code dispatchAs} function. A stage that takes longer fails and the
     * message is retried. The default is 5000 and the least accepted is 1; a smaller value fails when
     * the source is built.
     *
     * @param timeoutMs the bound in milliseconds
     * @return this builder
     */
    SELF lookupTimeoutMs(long timeoutMs);

    /**
     * Chooses the identity a message is recorded under. An empty answer keeps the identity the
     * transport supplies. Failing with {@link dev.vertique.workflow.source.SignalSourceRefusal}
     * refuses the message outright.
     *
     * @param identity answers the identity for the message
     * @return this builder
     */
    SELF dispatchAs(Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> identity);

    /**
     * Restores the correlation of a message from its headers, where the transport supports it.
     *
     * @return this builder
     */
    SELF restoreCorrelationFromHeaders();

    /**
     * Starts a signal of a contract. It must be followed by exactly one key source.
     *
     * @param contract the workflow contract that declares the signal
     * @param signalName the name of the signal
     * @return the stage that offers the key sources
     */
    SignalTargetStage<T, SELF, R> signal(Class<?> contract, String signalName);

    /**
     * Ends the source and returns what the transport runs. Fails when the source breaks a construction
     * rule: a signal with no key source, a message type that differs from the signal's parameter type
     * with no payload mapping, a business-key signal with no dedup key, a scope beside any-scope, a
     * lookup timeout below 1, or a signal the contract does not declare. Calling it again returns the
     * same instance, and a stage called after it fails.
     *
     * @return the built source
     */
    R build();
}
