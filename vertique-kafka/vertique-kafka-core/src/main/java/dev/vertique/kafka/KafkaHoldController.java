// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import io.vertx.core.Future;
import io.vertx.kafka.client.common.TopicPartition;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

    /** One assigned partition. The object is the identity of the assignment. */
    private static final class Slot {

        final TopicPartition partition;
        final EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        boolean applied;
        boolean applying;
        boolean ended;

        Slot(TopicPartition partition) {
            this.partition = partition;
        }
    }

    private final String name;
    private final KafkaSettlement.Port port;
    private final Map<TopicPartition, Slot> slots = new HashMap<>();
    private final EnumSet<Reason> everywhere = EnumSet.noneOf(Reason.class);

    KafkaHoldController(String name, KafkaSettlement.Port port) {
        this.name = name;
        this.port = port;
    }

    /** A partition is assigned: it starts unpaused in the client, so reapply what is held everywhere. */
    void assigned(TopicPartition partition) {
        Slot old = slots.remove(partition);
        if (old != null) {
            old.ended = true;
        }
        Slot slot = new Slot(partition);
        slot.reasons.addAll(everywhere);
        slots.put(partition, slot);
        apply(slot);
    }

    /** A partition is revoked: nothing is applied to it any more. */
    void revoked(TopicPartition partition) {
        Slot slot = slots.remove(partition);
        if (slot != null) {
            slot.ended = true;
        }
    }

    /** Adds a reason to one partition. */
    void hold(TopicPartition partition, Reason reason) {
        Slot slot = slots.get(partition);
        if (slot != null && slot.reasons.add(reason)) {
            apply(slot);
        }
    }

    /** Removes a reason from one partition. */
    void release(TopicPartition partition, Reason reason) {
        Slot slot = slots.get(partition);
        if (slot != null && slot.reasons.remove(reason)) {
            apply(slot);
        }
    }

    /** Adds a reason to every assigned partition, and to every partition assigned while it is held. */
    void holdAll(Reason reason) {
        if (everywhere.add(reason)) {
            for (Slot slot : slots.values()) {
                if (slot.reasons.add(reason)) {
                    apply(slot);
                }
            }
        }
    }

    /** Removes a reason from every assigned partition. */
    void releaseAll(Reason reason) {
        if (everywhere.remove(reason)) {
            for (Slot slot : slots.values()) {
                if (slot.reasons.remove(reason)) {
                    apply(slot);
                }
            }
        }
    }

    /** Whether a partition is held for a reason. */
    boolean isHeld(TopicPartition partition, Reason reason) {
        Slot slot = slots.get(partition);
        return slot != null && slot.reasons.contains(reason);
    }

    /** The reasons a partition is held for; empty when it fetches or is not assigned. */
    Set<Reason> reasons(TopicPartition partition) {
        Slot slot = slots.get(partition);
        return slot == null ? Set.of() : EnumSet.copyOf(slot.reasons);
    }

    /** Whether the client has been told to pause the partition, and no call is pending. */
    boolean isPaused(TopicPartition partition) {
        Slot slot = slots.get(partition);
        return slot != null && slot.applied && !slot.applying;
    }

    private void apply(Slot slot) {
        if (slot.ended || slot.applying) {
            return;
        }
        boolean wanted = !slot.reasons.isEmpty();
        if (wanted == slot.applied) {
            return;
        }
        slot.applying = true;
        Future<Void> call;
        try {
            call = wanted ? port.pause(slot.partition) : port.resume(slot.partition);
        } catch (Throwable thrown) {
            call = Future.failedFuture(thrown);
        }
        call.onComplete(result -> port.execute(() -> {
            slot.applying = false;
            if (slot.ended) {
                return;
            }
            if (result.succeeded()) {
                slot.applied = wanted;
                apply(slot);
            } else {
                log.warn(
                        "[{}] Could not {} partition {}; trying again",
                        name,
                        wanted ? "pause" : "resume",
                        slot.partition,
                        result.cause());
                port.schedule(APPLY_RETRY_MS, () -> apply(slot));
            }
        }));
    }
}
