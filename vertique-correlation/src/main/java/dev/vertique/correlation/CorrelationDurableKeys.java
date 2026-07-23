// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

/**
 * Durable metadata namespace used by {@code CorrelationContextDurableEncoder} / {@code Decoder}.
 *
 * <p>{@link #CORRELATION} is the single {@link dev.vertique.core.context.DurableMetadata} namespace
 * for the whole correlation context — its body is a Jackson-serialised {@link CorrelationEnvelope}
 * JSON object. A single namespace gives atomic round-trips through every durable carrier (outbox /
 * Kafka / delayed-job / workflow-timer metadata) and one {@code schemaVersion} to evolve. At the
 * Kafka boundary the namespace projects to the reserved header {@code vertique-correlation} (the
 * {@code vertique-} prefix is owned by {@link dev.vertique.core.context.DurableMetadataHeaderCodec},
 * not encoded into the namespace name).
 */
public final class CorrelationDurableKeys {

    /**
     * The {@link dev.vertique.core.context.DurableMetadata} namespace carrying the
     * {@link CorrelationEnvelope} JSON body for the whole correlation context.
     */
    public static final String CORRELATION = "correlation";

    private CorrelationDurableKeys() {}
}
