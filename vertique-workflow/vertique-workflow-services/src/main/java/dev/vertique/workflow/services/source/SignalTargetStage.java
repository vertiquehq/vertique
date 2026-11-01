// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.workflow.source.SignalMessage;
import io.vertx.core.Future;
import java.util.Optional;
import java.util.function.Function;

/**
 * The key sources a signal offers, of which exactly one follows each {@code signal}.
 *
 * <p>A key is non-blank, at most 255 characters, and holds no NUL character. A null or empty key, or a
 * lookup that answers empty, means the message concerns no workflow.
 *
 * @param <T> the type of the decoded message body
 * @param <SELF> the type of the builder
 * @param <R> what {@code build()} returns on the transport
 */
public interface SignalTargetStage<T, SELF, R> {

    /**
     * Addresses the signal to the instance waiting on a key taken from the decoded message.
     *
     * @param key answers the key from the message
     * @return the stage that follows a key source
     */
    KeyedTargetStage<T, SELF, R> key(Function<T, String> key);

    /**
     * Addresses the signal to the instance waiting on a key that a lookup answers.
     *
     * @param key answers the key, later, from the message
     * @return the stage that follows a key source
     */
    KeyedTargetStage<T, SELF, R> keyFrom(Function<SignalMessage<T>, Future<Optional<String>>> key);

    /**
     * Addresses the signal to the instance with a business key taken from the decoded message. The
     * source needs a {@code dedupBy}.
     *
     * @param key answers the business key from the message
     * @return the stage that follows a key source
     */
    KeyedTargetStage<T, SELF, R> businessKey(Function<T, String> key);

    /**
     * Addresses the signal to the instance with a business key that a lookup answers. The source needs
     * a {@code dedupBy}.
     *
     * @param key answers the business key, later, from the message
     * @return the stage that follows a key source
     */
    KeyedTargetStage<T, SELF, R> businessKeyFrom(Function<SignalMessage<T>, Future<Optional<String>>> key);
}
