// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Thrown by a {@link dev.vertique.core.context.DurableContextMetadataEncoder} to reject the entire
 * enclosing durable-context capture/merge operation, rather than merely skipping its own namespace.
 *
 * <p>Distinct from an encoder returning {@link dev.vertique.core.context.DurableMetadata#empty()},
 * which signals only "nothing legitimate to encode for this namespace" and lets the enclosing
 * produce/enqueue operation proceed without it — throwing this exception fails the enclosing
 * operation outright (e.g. {@code DurableContextPropagator#mergeCaptured}), propagating synchronously
 * out to the caller. A durable-job scheduler or other producer surfaces it as a rejected enqueue
 * (typically a failed {@code Future}) rather than persisting a document the encoder has determined is
 * unsafe to write — for example, an identity snapshot whose signed expiry would already have passed
 * by the time a scheduled dispatch fires.
 *
 * <p>The exception message must describe the rejection reason without echoing secret material.
 */
public class DurableEncodeRejectedException extends BusinessRuleException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message describing why the encode operation was rejected
     */
    public DurableEncodeRejectedException(String message) {
        super(message);
    }
}
