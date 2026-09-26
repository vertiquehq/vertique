// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * The {@link KafkaProducer @KafkaProducer} operation behind a direct-producer send.
 *
 * <p>{@code method.getDeclaringClass()} is the interface that <em>declares</em> the send method, which
 * for a method inherited from a super-interface is not the producer interface the application
 * injected. {@link #producerType()} is that producer interface, so a capture hook reads type-level
 * annotations from it and method-level annotations from {@link #method()}.
 *
 * @param producerType the {@code @KafkaProducer} interface the sending proxy was created for; never
 *                     {@code null}
 * @param producerName the producer's name ({@code @KafkaProducer.name()}, or the interface's simple
 *                     name when blank); never {@code null}
 * @param method       the interface method that initiated the send; never {@code null}
 */
public record KafkaProducerOperation(Class<?> producerType, String producerName, Method method) {

    /** Rejects a missing component. */
    public KafkaProducerOperation {
        Objects.requireNonNull(producerType, "producerType");
        Objects.requireNonNull(producerName, "producerName");
        Objects.requireNonNull(method, "method");
    }
}
