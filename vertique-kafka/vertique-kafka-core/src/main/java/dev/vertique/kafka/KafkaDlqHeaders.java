// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * The names of the headers the consumer error handling writes on a record it republishes to a
 * dead-letter topic. Each value is UTF-8 text.
 */
public final class KafkaDlqHeaders {

    /** The topic the failed record was consumed from. */
    public static final String SOURCE_TOPIC = "x-dlq-source-topic";

    /** The partition the failed record was consumed from. */
    public static final String SOURCE_PARTITION = "x-dlq-source-partition";

    /** The offset of the failed record. */
    public static final String SOURCE_OFFSET = "x-dlq-source-offset";

    /** The name of the consumer that failed to process the record. */
    public static final String CONSUMER = "x-dlq-consumer";

    /** The simple name of the failure, followed by the start of its message. */
    public static final String ERROR = "x-dlq-error";

    private KafkaDlqHeaders() {}
}
