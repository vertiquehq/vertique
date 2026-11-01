// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

/**
 * The only path by which a {@code MANUAL} consumer commits an offset. It commits the
 * {@linkplain KafkaPartitionLedger#watermark() watermark} of a partition's ledger: the offset after the longest prefix
 * of records that have all finished, never the position the client has read to and never the offset of the record
 * that happened to finish last.
 *
 * <p>One commit is in flight per ledger. The offset it carries is fixed when it is submitted, and the
 * acknowledgment records that offset and not the watermark of the moment, so a watermark that moved meanwhile is
 * committed by the next submit. A commit that fails clears the in-flight mark and is submitted again after a short
 * wait, with the watermark as it is then. A commit or an acknowledgment of an ended ledger changes nothing.
 *
 * <p>The controller is not thread-safe. The consumer's owner context is the only caller.
 */
@Slf4j
final class KafkaCommitController {

    /** How long a failed commit waits before the watermark is submitted again. */
    static final long RETRY_MS = 1_000L;

    private final String name;
    private final KafkaSettlement.Port port;
    private int outstanding;

    KafkaCommitController(String name, KafkaSettlement.Port port) {
        this.name = name;
        this.port = port;
    }

    /** The commits submitted and not yet answered, of any assignment. */
    int outstanding() {
        return outstanding;
    }

    /** Submits the watermark of a ledger when it is above what the broker acknowledged and no commit is in flight. */
    void commit(KafkaPartitionLedger ledger) {
        if (ledger.ended() || ledger.pendingCommit() != KafkaPartitionLedger.NONE) {
            return;
        }
        long offset = ledger.watermark();
        if (offset == KafkaPartitionLedger.NONE || offset <= ledger.lastAcked()) {
            return;
        }
        ledger.pendingCommit(offset);
        outstanding++;
        Future<Void> call;
        try {
            call = port.commit(ledger.partition(), offset);
        } catch (Throwable thrown) {
            call = Future.failedFuture(thrown);
        }
        call.onComplete(result -> port.execute(() -> answered(ledger, offset, result.succeeded(), result.cause())));
    }

    /**
     * Submits the watermark a last time, as a revoked partition's best effort, and ends the ledger. A commit already
     * in flight is left to finish and nothing more is sent.
     */
    void revoke(KafkaPartitionLedger ledger) {
        commit(ledger);
        ledger.end();
    }

    private void answered(KafkaPartitionLedger ledger, long offset, boolean succeeded, Throwable cause) {
        outstanding--;
        if (ledger.ended()) {
            return;
        }
        ledger.pendingCommit(KafkaPartitionLedger.NONE);
        if (succeeded) {
            ledger.acknowledged(offset);
            commit(ledger);
        } else {
            log.warn(
                    "[{}] Failed to commit offset {} of {}; the watermark is submitted again",
                    name,
                    offset,
                    ledger.partition(),
                    cause);
            port.schedule(RETRY_MS, () -> commit(ledger));
        }
    }
}
