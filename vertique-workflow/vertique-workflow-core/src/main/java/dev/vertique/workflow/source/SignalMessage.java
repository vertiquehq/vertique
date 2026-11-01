// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.util.Optional;

/**
 * One message a correlated signal source received, as the source's stages see it.
 *
 * <p>The same type serves every transport: the decoded {@link #body()}, a transport header by name,
 * and on Kafka the record key.
 *
 * @param <T> the type of the decoded message body
 */
public interface SignalMessage<T> {

    /**
     * Returns the decoded message body.
     *
     * @return the body
     */
    T body();

    /**
     * Returns the value of a transport header.
     *
     * @param name the name of the header
     * @return the value, or empty when the message carries no such header
     */
    Optional<String> header(String name);

    /**
     * Returns the Kafka record key.
     *
     * @return the record key, or empty when the message has none; always empty on Camel
     */
    Optional<String> key();
}
