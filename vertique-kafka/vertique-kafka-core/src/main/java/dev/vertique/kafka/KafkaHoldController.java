// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import io.vertx.core.Future;
import java.util.Collection;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides, per partition, whether the consumer fetches, and applies that to the client with its partition pause and
 * resume, so the consumer keeps polling and keeps its group membership however many partitions it holds.
 *
 * <p>A partition fetches only while it has no {@link Reason}. Each reason is added and removed alone and is not
 * counted: a reason that more than one record wants, such as the backoff of two overlapping retries, is derived by the
 * caller from its own state, so a surplus removal cannot release another record's hold.
 *
 * <p>The controller applies the state it wants, one client call per partition at a time, and applies it again when the
 * call fails, when the state changed meanwhile and when the partition is assigned again. A call that finishes after the
 * partition was revoked, or revoked and assigned again, changes nothing.
 *
 * <p>The controller is not thread-safe. The consumer's owner context is the only caller.
 */
@Slf4j
final class KafkaHoldController {

    /** Why a partition does not fetch. */
    enum Reason {

        /** The partition or the consumer holds as many records as its retention bound allows. */
        RETENTION,

        /** The dispatch gate is closed, so no record starts and fetching more would only queue it. */
        INTAKE,

        /** A record of an ordinary binding waits out its retry backoff. Derived from the partition's retry waits. */
        BACKOFF,

        /** The consumer is stopping. */
        SHUTDOWN
    }

    /** How long a failed pause or resume waits before it is applied again. */
    static final long APPLY_RETRY_MS = 500L;

    private final String name;
    private final KafkaSettlement.Port port;
    private final EnumSet<Reason> everywhere = EnumSet.noneOf(Reason.class);

    KafkaHoldController(String name, KafkaSettlement.Port port) {
        this.name = name;
        this.port = port;
    }

    /**
     * A partition is assigned. What the client holds for it is not known: a pause or resume that was queued for the
     * partition's previous assignment, ahead of the poll that reassigned it, can land on this one, and a call carries
     * no assignment identity. The first call for the new assignment is therefore always made, a resume when nothing is
     * held, and it queues behind that older call.
     */
    void assigned(KafkaPartitionLedger ledger) {
        ledger.reasons.addAll(everywhere);
        apply(ledger);
    }

    /** Adds a reason to one partition. */
    void hold(KafkaPartitionLedger ledger, Reason reason) {
        if (ledger.reasons.add(reason)) {
            apply(ledger);
        }
    }

    /** Removes a reason from one partition. */
    void release(KafkaPartitionLedger ledger, Reason reason) {
        if (ledger.reasons.remove(reason)) {
            apply(ledger);
        }
    }

    /** Adds a reason to every assigned partition, and to every partition assigned while it is held. */
    void holdAll(Reason reason, Collection<KafkaPartitionLedger> assigned) {
        if (everywhere.add(reason)) {
            for (KafkaPartitionLedger ledger : assigned) {
                hold(ledger, reason);
            }
        }
    }

    /** Removes a reason from every assigned partition. */
    void releaseAll(Reason reason, Collection<KafkaPartitionLedger> assigned) {
        if (everywhere.remove(reason)) {
            for (KafkaPartitionLedger ledger : assigned) {
                release(ledger, reason);
            }
        }
    }

    private void apply(KafkaPartitionLedger ledger) {
        if (ledger.ended() || ledger.applying) {
            return;
        }
        boolean wanted = !ledger.reasons.isEmpty();
        if (ledger.applied != null && wanted == ledger.applied) {
            return;
        }
        ledger.applying = true;
        Future<Void> call;
        try {
            call = wanted ? port.pause(ledger.partition()) : port.resume(ledger.partition());
        } catch (Throwable thrown) {
            call = Future.failedFuture(thrown);
        }
        call.onComplete(result -> port.execute(() -> {
            ledger.applying = false;
            if (ledger.ended()) {
                return;
            }
            if (result.succeeded()) {
                ledger.applied = wanted;
                apply(ledger);
            } else {
                log.warn(
                        "[{}] Could not {} partition {}; trying again",
                        name,
                        wanted ? "pause" : "resume",
                        ledger.partition(),
                        result.cause());
                port.schedule(APPLY_RETRY_MS, () -> apply(ledger));
            }
        }));
    }
}
