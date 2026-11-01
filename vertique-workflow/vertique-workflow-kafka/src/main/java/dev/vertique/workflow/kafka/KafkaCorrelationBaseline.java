// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationHeaderValidator;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextDurableDecoder;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.kafka.KafkaRecordHeader;
import dev.vertique.kafka.KafkaRecordHeaders;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The correlation a record is delivered under on a source that opted in with
 * {@code restoreCorrelationFromHeaders()}.
 *
 * <p>The rule is the one the Camel lifecycle follows. Only the exact {@code vertique-correlation}
 * header is read; a value longer than {@value #MAX_HEADER_LENGTH} characters is ignored unparsed; the
 * value is decoded alone, so no identity, locale or application context header can be restored; the
 * correlation id alone is taken, and only when {@link CorrelationHeaderValidator} accepts it; and it
 * joins a freshly seeded context, which keeps its own request id. A header that is missing, too long,
 * malformed or invalid is ignored: it never fails the record and its value is never logged.
 */
final class KafkaCorrelationBaseline {

    /** The longest header value, in characters, a correlation id is restored from. */
    static final int MAX_HEADER_LENGTH = 4096;

    /** The source label of a correlation id restored from a header. */
    static final String RESTORED_SOURCE = "restored:" + DispatchBoundary.KAFKA;

    private static final Logger log = LoggerFactory.getLogger(KafkaCorrelationBaseline.class);

    private static final DurableDecodeContext DECODE_CONTEXT = new DurableDecodeContext(DispatchBoundary.KAFKA);

    private final CorrelationContextFactory factory;
    private final CorrelationContextDurableDecoder decoder;

    /** The exact name of the header that carries the correlation, as the codec writes it. */
    private final String header;

    KafkaCorrelationBaseline(CorrelationContextFactory factory) {
        this.factory = factory;
        this.decoder = new CorrelationContextDurableDecoder(factory);
        this.header = DurableMetadataHeaderCodec.RESERVED_PREFIX + decoder.namespace();
    }

    /** A baseline over the default correlation id generator. */
    static KafkaCorrelationBaseline create() {
        return new KafkaCorrelationBaseline(new CorrelationContextFactory(Optional.empty()));
    }

    /**
     * Returns the seeded correlation, with the restored correlation id when the header carries a valid one.
     *
     * @param sourceName the name of the source, for the diagnostic
     * @param headers the record's headers
     * @return the correlation to bind; never {@code null}
     */
    CorrelationContext baseline(String sourceName, KafkaRecordHeaders headers) {
        CorrelationContext seeded = factory.seed(DispatchBoundary.KAFKA);
        String encoded = textOf(headers);
        if (encoded == null) {
            return seeded;
        }
        if (encoded.length() > MAX_HEADER_LENGTH) {
            log.debug(
                    "ignoring a '{}' header of {} characters for source '{}'; the limit is {}",
                    header,
                    encoded.length(),
                    sourceName,
                    MAX_HEADER_LENGTH);
            return seeded;
        }
        try {
            Optional<CorrelationContext> decoded = decoder.decode(
                            DurableMetadataHeaderCodec.fromHeaders(Map.of(header, encoded)), DECODE_CONTEXT)
                    .value();
            if (decoded.isEmpty()) {
                return seeded;
            }
            // Only the id's value is taken; everything else in the decoded context is dropped.
            String correlationId = decoded.get().correlationId().value();
            if (!CorrelationHeaderValidator.isValidHeaderValue(correlationId)) {
                log.debug(
                        "ignoring a '{}' header for source '{}': its correlation id is not valid", header, sourceName);
                return seeded;
            }
            return factory.create(seeded.requestId(), new CorrelationIdentifier(correlationId, RESTORED_SOURCE));
        } catch (RuntimeException e) {
            // Input from outside: never fail the record over it, never log its value.
            log.debug(
                    "ignoring an undecodable '{}' header for source '{}': {}",
                    header,
                    sourceName,
                    e.getClass().getSimpleName());
            return seeded;
        }
    }

    private @Nullable String textOf(KafkaRecordHeaders headers) {
        Optional<KafkaRecordHeader> last = headers.lastHeader(header);
        if (last.isEmpty() || !last.get().hasValue()) {
            return null;
        }
        try {
            return last.get().valueAsUtf8();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
